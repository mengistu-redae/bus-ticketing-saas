package com.bustix.cargo;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.EffectiveOperatorSettings;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.operator.OperatorSettingsService;
import com.bustix.refund.RefundCalculator;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Orchestrates the waybill lifecycle - see
 * my-notes/cargo_logistics_scope_v1.md for the full design. Each public
 * method here is its own @Transactional boundary (same shape as
 * CancellationService's cancel/cancelAsCustomer - no self-invocation
 * between them, so no need for a separate Writer bean the way
 * BookingService/BookingWriter split for the Redis-locking reason).
 */
@Service
public class CargoWaybillService {

    private final CargoWaybillRepository cargoWaybillRepository;
    private final CargoWaybillItemRepository cargoWaybillItemRepository;
    private final CargoWaybillCancellationRepository cancellationRepository;
    private final CargoRateRepository cargoRateRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final RouteRepository routeRepository;
    private final OperatorRepository operatorRepository;
    private final WaybillNumberGenerator waybillNumberGenerator;
    private final ProhibitedItemsChecker prohibitedItemsChecker;
    private final RefundCalculator refundCalculator;
    private final OperatorSettingsService operatorSettingsService;

    public CargoWaybillService(
            CargoWaybillRepository cargoWaybillRepository,
            CargoWaybillItemRepository cargoWaybillItemRepository,
            CargoWaybillCancellationRepository cancellationRepository,
            CargoRateRepository cargoRateRepository,
            TripRepository tripRepository,
            BookingRepository bookingRepository,
            RouteRepository routeRepository,
            OperatorRepository operatorRepository,
            WaybillNumberGenerator waybillNumberGenerator,
            ProhibitedItemsChecker prohibitedItemsChecker,
            RefundCalculator refundCalculator,
            OperatorSettingsService operatorSettingsService) {
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.cargoWaybillItemRepository = cargoWaybillItemRepository;
        this.cancellationRepository = cancellationRepository;
        this.cargoRateRepository = cargoRateRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.routeRepository = routeRepository;
        this.operatorRepository = operatorRepository;
        this.waybillNumberGenerator = waybillNumberGenerator;
        this.prohibitedItemsChecker = prohibitedItemsChecker;
        this.refundCalculator = refundCalculator;
        this.operatorSettingsService = operatorSettingsService;
    }

    @Transactional
    public CargoWaybill create(CreateWaybillRequest request, UUID tenantId, UUID issuedByUserId) {
        Trip trip = tripRepository.findByIdAndTenantId(request.tripId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + request.tripId()));

        if (request.bookingId() != null) {
            Booking booking = bookingRepository.findByIdAndTenantId(request.bookingId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Booking not found: " + request.bookingId()));
            if (!booking.getTripId().equals(trip.getId())) {
                throw new BookingTripMismatchException(
                        "Booking " + booking.getId() + " is on a different trip than this waybill");
            }
        }

        if (request.description() != null) {
            prohibitedItemsChecker.check(request.description());
        }
        for (CreateWaybillRequest.ItemRequest item : request.items()) {
            prohibitedItemsChecker.check(item.description());
        }

        BigDecimal totalWeight = sumWeight(request.items());
        Pricing pricing = calculatePricing(tenantId, trip.getRouteId(), totalWeight);

        String operatorName = operatorRepository.findById(tenantId).map(Operator::getName).orElse("Unknown");

        CargoWaybill waybill = new CargoWaybill();
        waybill.setTenantId(tenantId);
        waybill.setTripId(trip.getId());
        waybill.setBookingId(request.bookingId());
        waybill.setWaybillNumber(waybillNumberGenerator.nextWaybillNumber(operatorName));
        waybill.setConsignorName(request.consignorName());
        waybill.setConsignorPhone(request.consignorPhone());
        waybill.setConsignorIdNumber(request.consignorIdNumber());
        waybill.setConsigneeName(request.consigneeName());
        waybill.setConsigneePhone(request.consigneePhone());
        waybill.setConsigneeIdNumber(request.consigneeIdNumber());
        waybill.setDescription(request.description());
        waybill.setDeclaredValue(sumDeclaredValue(request.items()));
        waybill.setGrossWeightKg(totalWeight);
        applyPricing(waybill, pricing);
        waybill.setStatus("issued");
        waybill.setIssuedBy(issuedByUserId);

        waybill = cargoWaybillRepository.save(waybill);
        saveItems(waybill.getId(), request.items());

        return waybill;
    }

    /**
     * Partial update. Physical-shipment fields only apply while
     * status = "issued" (decision 11) - paymentStatus is exempt from that
     * freeze and applicable at any status.
     */
    @Transactional
    public CargoWaybill update(UUID waybillId, UUID tenantId, UpdateWaybillRequest request) {
        CargoWaybill waybill = findOwnedWaybill(waybillId, tenantId);

        boolean touchesPhysicalFields = request.consignorName() != null
                || request.consignorPhone() != null
                || request.consignorIdNumber() != null
                || request.consigneeName() != null
                || request.consigneePhone() != null
                || request.consigneeIdNumber() != null
                || request.description() != null
                || request.items() != null;

        if (touchesPhysicalFields && !"issued".equals(waybill.getStatus())) {
            throw new InvalidWaybillStatusException(
                    "Waybill " + waybillId + " is " + waybill.getStatus()
                            + " - physical-shipment details are frozen once dispatched");
        }

        if (request.consignorName() != null) {
            waybill.setConsignorName(request.consignorName());
        }
        if (request.consignorPhone() != null) {
            waybill.setConsignorPhone(request.consignorPhone());
        }
        if (request.consignorIdNumber() != null) {
            waybill.setConsignorIdNumber(request.consignorIdNumber());
        }
        if (request.consigneeName() != null) {
            waybill.setConsigneeName(request.consigneeName());
        }
        if (request.consigneePhone() != null) {
            waybill.setConsigneePhone(request.consigneePhone());
        }
        if (request.consigneeIdNumber() != null) {
            waybill.setConsigneeIdNumber(request.consigneeIdNumber());
        }
        if (request.description() != null) {
            prohibitedItemsChecker.check(request.description());
            waybill.setDescription(request.description());
        }
        if (request.items() != null) {
            if (request.items().isEmpty()) {
                throw new InvalidWaybillItemsException("A waybill must have at least one item");
            }
            for (CreateWaybillRequest.ItemRequest item : request.items()) {
                prohibitedItemsChecker.check(item.description());
            }

            BigDecimal totalWeight = sumWeight(request.items());
            waybill.setGrossWeightKg(totalWeight);
            waybill.setDeclaredValue(sumDeclaredValue(request.items()));

            Trip trip = tripRepository.findById(waybill.getTripId())
                    .orElseThrow(() -> new NoSuchElementException("Trip not found: " + waybill.getTripId()));
            // Re-priced against whatever cargo_rates row resolves *now* -
            // acceptable for a pre-dispatch same-day correction, see
            // CargoWaybillService's own javadoc / the scope doc's decision 3.
            applyPricing(waybill, calculatePricing(tenantId, trip.getRouteId(), totalWeight));

            cargoWaybillItemRepository.deleteAllByWaybillId(waybillId);
            saveItems(waybillId, request.items());
        }
        if (request.paymentStatus() != null) {
            waybill.setPaymentStatus(request.paymentStatus());
        }

        return cargoWaybillRepository.save(waybill);
    }

    @Transactional
    public CargoWaybill dispatch(UUID waybillId, UUID tenantId) {
        CargoWaybill waybill = findOwnedWaybill(waybillId, tenantId);
        if ("dispatched".equals(waybill.getStatus()) || "arrived".equals(waybill.getStatus())
                || "collected".equals(waybill.getStatus())) {
            return waybill; // idempotent re-call, same convention as BoardingService.checkIn
        }
        if (!"issued".equals(waybill.getStatus())) {
            throw new InvalidWaybillStatusException(
                    "Cannot dispatch waybill " + waybillId + " - currently " + waybill.getStatus());
        }
        waybill.setStatus("dispatched");
        waybill.setDispatchedAt(Instant.now());
        return cargoWaybillRepository.save(waybill);
    }

    @Transactional
    public CargoWaybill arrive(UUID waybillId, UUID tenantId) {
        CargoWaybill waybill = findOwnedWaybill(waybillId, tenantId);
        if ("arrived".equals(waybill.getStatus()) || "collected".equals(waybill.getStatus())) {
            return waybill;
        }
        if (!"dispatched".equals(waybill.getStatus())) {
            throw new InvalidWaybillStatusException(
                    "Cannot mark waybill " + waybillId + " arrived - currently " + waybill.getStatus()
                            + " (must be dispatched first)");
        }
        waybill.setStatus("arrived");
        waybill.setArrivedAt(Instant.now());
        return cargoWaybillRepository.save(waybill);
    }

    @Transactional
    public CargoWaybill collect(UUID waybillId, UUID tenantId, String presentedIdNumber) {
        CargoWaybill waybill = findOwnedWaybill(waybillId, tenantId);
        if ("collected".equals(waybill.getStatus())) {
            return waybill; // idempotent re-call, same as BoardingService.checkIn
        }
        if (!"arrived".equals(waybill.getStatus())) {
            throw new InvalidWaybillStatusException(
                    "Cannot collect waybill " + waybillId + " - currently " + waybill.getStatus()
                            + " (must have arrived first)");
        }

        String onFile = waybill.getConsigneeIdNumber();
        if (onFile == null || onFile.isBlank() || !onFile.equals(presentedIdNumber)) {
            throw new ConsigneeIdentityMismatchException(
                    "Presented ID does not match the ID on file for waybill " + waybillId);
        }

        waybill.setStatus("collected");
        waybill.setCollectedAt(Instant.now());
        waybill.setConsigneeIdVerified(true);
        return cargoWaybillRepository.save(waybill);
    }

    /**
     * Only allowed pre-dispatch (decision 8) - post-dispatch cargo is
     * physically on a moving bus. Reuses RefundCalculator as-is (it's
     * already generic, not booking-specific) against the same
     * refund_policies an operator already configures for passenger
     * bookings.
     */
    @Transactional
    public CargoWaybillCancellation cancel(UUID waybillId, UUID tenantId, UUID cancelledByUserId, String reason) {
        CargoWaybill waybill = findOwnedWaybill(waybillId, tenantId);

        if ("cancelled".equals(waybill.getStatus())) {
            throw new WaybillAlreadyCancelledException("Waybill already cancelled: " + waybillId);
        }
        if (!"issued".equals(waybill.getStatus())) {
            throw new InvalidWaybillStatusException(
                    "Cannot cancel waybill " + waybillId + " - currently " + waybill.getStatus()
                            + " (only an undispatched waybill can be cancelled)");
        }

        Trip trip = tripRepository.findById(waybill.getTripId())
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + waybill.getTripId()));

        BigDecimal refundAmount = refundCalculator.calculate(
                tenantId, trip.getRouteId(), waybill.getTotalCargoCost(), trip.getDepartureAt());

        waybill.setStatus("cancelled");
        cargoWaybillRepository.save(waybill);

        CargoWaybillCancellation cancellation = new CargoWaybillCancellation();
        cancellation.setWaybillId(waybill.getId());
        cancellation.setCancelledBy(cancelledByUserId);
        cancellation.setReason(reason);
        cancellation.setRefundAmount(refundAmount);
        return cancellationRepository.save(cancellation);
    }

    /**
     * Customer self-service shipment request (POST /api/my-shipments) - no
     * trip, no tenant, no pricing yet, status "requested". Staff reviews
     * and prices it via confirmAndIssue. Items are customer-declared
     * estimates, still checked against ProhibitedItemsChecker - a customer
     * shouldn't be able to request shipping a prohibited item any more
     * than staff can create one.
     */
    @Transactional
    public CargoWaybill requestShipment(CreateShipmentRequest request, UUID customerUserId) {
        if (request.description() != null) {
            prohibitedItemsChecker.check(request.description());
        }
        for (CreateWaybillRequest.ItemRequest item : request.items()) {
            prohibitedItemsChecker.check(item.description());
        }

        // The customer routes the request to one operator up front - the
        // waybill is tenant-scoped from creation, so it only ever shows up in
        // that operator's /api/cargo/requests inbox. (tripId still stays null
        // until confirmAndIssue - the operator picks the actual bus then.)
        Operator operator = operatorRepository.findById(request.operatorId())
                .filter(o -> "active".equals(o.getStatus()))
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + request.operatorId()));

        CargoWaybill waybill = new CargoWaybill();
        waybill.setTenantId(operator.getId());
        waybill.setCustomerUserId(customerUserId);
        waybill.setWaybillNumber(waybillNumberGenerator.nextWaybillNumber("REQ"));
        waybill.setConsignorName(request.consignorName());
        waybill.setConsignorPhone(request.consignorPhone());
        waybill.setConsignorIdNumber(request.consignorIdNumber());
        waybill.setConsigneeName(request.consigneeName());
        waybill.setConsigneePhone(request.consigneePhone());
        waybill.setConsigneeIdNumber(request.consigneeIdNumber());
        waybill.setDescription(request.description());
        waybill.setDeclaredValue(sumDeclaredValue(request.items()));
        waybill.setGrossWeightKg(sumWeight(request.items()));
        waybill.setStatus("requested");

        waybill = cargoWaybillRepository.save(waybill);
        saveItems(waybill.getId(), request.items());
        return waybill;
    }

    /**
     * Staff review of a "requested" waybill: assigns the real trip
     * (tenant-scoped - the first point this waybill gets an operator),
     * optionally corrects the consignee ID / re-weighs the items after
     * physically inspecting the shipment, computes real pricing, and flips
     * requested -> issued. Idempotent past "requested" (already-issued-or-
     * later just returns current state, same convention as dispatch/
     * arrive/collect), 409s via RequestNotIssuableException on anything
     * else (out of order, or a still-missing consigneeIdNumber).
     */
    @Transactional
    public CargoWaybill confirmAndIssue(UUID waybillId, UUID tenantId, UUID issuedByUserId, ConfirmAndIssueWaybillRequest request) {
        // Tenant-scoped now that a "requested" waybill carries its intended
        // operator from creation (see requestShipment) - a request routed to
        // another operator 404s here just like every other cross-tenant
        // lookup in this app.
        CargoWaybill waybill = cargoWaybillRepository.findByIdAndTenantId(waybillId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));

        if (!"requested".equals(waybill.getStatus())) {
            if (!"cancelled".equals(waybill.getStatus())) {
                return waybill; // idempotent re-call once already issued (or later)
            }
            throw new RequestNotIssuableException(
                    "Waybill " + waybillId + " is " + waybill.getStatus()
                            + " - only a requested waybill can be confirmed and issued");
        }

        Trip trip = tripRepository.findByIdAndTenantId(request.tripId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + request.tripId()));

        String consigneeIdNumber = request.consigneeIdNumber() != null
                ? request.consigneeIdNumber() : waybill.getConsigneeIdNumber();
        if (consigneeIdNumber == null || consigneeIdNumber.isBlank()) {
            throw new RequestNotIssuableException("consigneeIdNumber is required to issue waybill " + waybillId);
        }

        List<CreateWaybillRequest.ItemRequest> items = request.items();
        if (items != null) {
            for (CreateWaybillRequest.ItemRequest item : items) {
                prohibitedItemsChecker.check(item.description());
            }
            cargoWaybillItemRepository.deleteAllByWaybillId(waybillId);
            saveItems(waybillId, items);
        } else {
            items = cargoWaybillItemRepository.findAllByWaybillId(waybillId).stream()
                    .map(i -> new CreateWaybillRequest.ItemRequest(i.getDescription(), i.getQuantity(), i.getDeclaredValue(), i.getGrossWeightKg()))
                    .toList();
        }

        BigDecimal totalWeight = sumWeight(items);
        Pricing pricing = calculatePricing(tenantId, trip.getRouteId(), totalWeight);

        waybill.setTenantId(tenantId);
        waybill.setTripId(trip.getId());
        waybill.setConsigneeIdNumber(consigneeIdNumber);
        waybill.setGrossWeightKg(totalWeight);
        waybill.setDeclaredValue(sumDeclaredValue(items));
        applyPricing(waybill, pricing);
        waybill.setStatus("issued");
        waybill.setIssuedBy(issuedByUserId);
        return cargoWaybillRepository.save(waybill);
    }

    /**
     * Public, unauthenticated - see decision 9. `phone` must match either
     * consignorPhone or consigneePhone; any other mismatch (unknown waybill
     * number, wrong phone) 404s identically, same "exists but not yours
     * reads as doesn't exist" rule as every ownership-scoped lookup in this
     * app.
     */
    @Transactional(readOnly = true)
    public WaybillTrackingView track(String waybillNumber, String phone) {
        CargoWaybill waybill = cargoWaybillRepository.findByWaybillNumber(waybillNumber)
                .filter(w -> w.getConsignorPhone().equals(phone) || w.getConsigneePhone().equals(phone))
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillNumber));

        Trip trip = tripRepository.findById(waybill.getTripId()).orElse(null);
        Route route = trip != null ? routeRepository.findById(trip.getRouteId()).orElse(null) : null;

        // waybill.getTenantId() is null only for a still-'requested' waybill,
        // which has no waybillNumber to look up here anyway - resolve()
        // tolerates null and returns platform defaults (both contacts null).
        EffectiveOperatorSettings settings = operatorSettingsService.resolve(waybill.getTenantId());

        return new WaybillTrackingView(
                waybill.getWaybillNumber(),
                waybill.getStatus(),
                waybill.getCreatedAt(),
                waybill.getDispatchedAt(),
                waybill.getArrivedAt(),
                waybill.getCollectedAt(),
                route != null ? route.getOrigin() : null,
                route != null ? route.getDestination() : null,
                trip != null ? trip.getDepartureAt() : null,
                settings.supportPhone(),
                settings.supportEmail());
    }

    private CargoWaybill findOwnedWaybill(UUID waybillId, UUID tenantId) {
        return cargoWaybillRepository.findByIdAndTenantId(waybillId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
    }

    private Pricing calculatePricing(UUID tenantId, UUID routeId, BigDecimal grossWeightKg) {
        CargoRate rate = cargoRateRepository.findByTenantIdAndRouteId(tenantId, routeId)
                .or(() -> cargoRateRepository.findByTenantIdAndRouteIdIsNull(tenantId))
                .orElseThrow(() -> new NoCargoRateConfiguredException(
                        "No cargo rate configured for route " + routeId));

        BigDecimal excessWeight = grossWeightKg.subtract(rate.getFreeWeightThresholdKg()).max(BigDecimal.ZERO);
        BigDecimal weightSurcharge = excessWeight.multiply(rate.getSurchargePerKg()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCost = rate.getBaseFreightCharge().add(weightSurcharge).add(rate.getHandlingFee());

        return new Pricing(excessWeight, rate.getBaseFreightCharge(), weightSurcharge, rate.getHandlingFee(), totalCost);
    }

    private void applyPricing(CargoWaybill waybill, Pricing pricing) {
        waybill.setExcessWeightKg(pricing.excessWeight());
        waybill.setBaseFreightCharge(pricing.baseFreightCharge());
        waybill.setWeightSurcharge(pricing.weightSurcharge());
        waybill.setHandlingServiceFee(pricing.handlingFee());
        waybill.setTotalCargoCost(pricing.totalCost());
    }

    private BigDecimal sumWeight(List<CreateWaybillRequest.ItemRequest> items) {
        return items.stream()
                .map(CreateWaybillRequest.ItemRequest::grossWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Null-safe sum - null only if every item left declaredValue unset, matching a single item's own "optional" semantics. */
    private BigDecimal sumDeclaredValue(List<CreateWaybillRequest.ItemRequest> items) {
        boolean anyPresent = items.stream().anyMatch(item -> item.declaredValue() != null);
        if (!anyPresent) {
            return null;
        }
        return items.stream()
                .map(item -> item.declaredValue() != null ? item.declaredValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void saveItems(UUID waybillId, List<CreateWaybillRequest.ItemRequest> items) {
        for (CreateWaybillRequest.ItemRequest item : items) {
            CargoWaybillItem entity = new CargoWaybillItem();
            entity.setWaybillId(waybillId);
            entity.setDescription(item.description());
            entity.setQuantity(item.quantity());
            entity.setDeclaredValue(item.declaredValue());
            entity.setGrossWeightKg(item.grossWeightKg());
            cargoWaybillItemRepository.save(entity);
        }
    }

    private record Pricing(
            BigDecimal excessWeight,
            BigDecimal baseFreightCharge,
            BigDecimal weightSurcharge,
            BigDecimal handlingFee,
            BigDecimal totalCost) {
    }
}
