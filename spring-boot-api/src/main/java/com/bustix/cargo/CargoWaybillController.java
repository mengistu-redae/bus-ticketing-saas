package com.bustix.cargo;

import com.bustix.tenant.TenantContext;
import com.bustix.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Staff-only waybill lifecycle (AGENT/OPERATOR_ADMIN, tenant-scoped) plus
 * one public, unauthenticated track-by-number endpoint - see
 * my-notes/cargo_logistics_scope_v1.md decisions 1/5/9/11. Returns
 * CargoWaybill entities directly from every staff endpoint, same
 * no-DTO-shaping convention as the rest of this API's fleet controllers.
 */
@RestController
public class CargoWaybillController {

    private final CargoWaybillService cargoWaybillService;
    private final CargoWaybillRepository cargoWaybillRepository;
    private final CargoWaybillItemRepository cargoWaybillItemRepository;
    private final CurrentUserService currentUserService;

    public CargoWaybillController(
            CargoWaybillService cargoWaybillService,
            CargoWaybillRepository cargoWaybillRepository,
            CargoWaybillItemRepository cargoWaybillItemRepository,
            CurrentUserService currentUserService) {
        this.cargoWaybillService = cargoWaybillService;
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.cargoWaybillItemRepository = cargoWaybillItemRepository;
        this.currentUserService = currentUserService;
    }

    /** CargoWaybill carries no JPA relation to its items - see WaybillWithItems's javadoc. */
    private WaybillWithItems withItems(CargoWaybill waybill) {
        return new WaybillWithItems(waybill, cargoWaybillItemRepository.findAllByWaybillId(waybill.getId()));
    }

    @PostMapping("/api/cargo/waybills")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems create(@Valid @RequestBody CreateWaybillRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID issuedByUserId = currentUserService.resolveInternalUserId(jwt);
        return withItems(cargoWaybillService.create(request, tenantId, issuedByUserId));
    }

    @GetMapping("/api/cargo/waybills")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<WaybillWithItems> list(
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        List<CargoWaybill> waybills;
        if (tripId != null) {
            waybills = cargoWaybillRepository.findAllByTenantIdAndTripId(tenantId, tripId);
        } else if (status != null) {
            waybills = cargoWaybillRepository.findAllByTenantIdAndStatus(tenantId, status);
        } else {
            waybills = cargoWaybillRepository.findAllByTenantId(tenantId);
        }
        return waybills.stream().map(this::withItems).toList();
    }

    /**
     * Tenant-scoped, with one deliberate exception: a still-"requested"
     * waybill has no tenant yet (see CargoWaybill's own javadoc), so it's
     * let through here too - any operator's staff needs to be able to open
     * it from the pending-requests inbox (GET /api/cargo/requests) to
     * review and confirm-and-issue it, the same "not yet scoped, visible to
     * anyone until claimed" reasoning findAllByStatusAndTenantIdIsNull
     * already uses for the list. Once issued, tenantId is set and this
     * falls back to the normal same-tenant-only check.
     */
    @GetMapping("/api/cargo/waybills/{waybillId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems get(@PathVariable UUID waybillId) {
        UUID tenantId = TenantContext.require();
        CargoWaybill waybill = cargoWaybillRepository.findById(waybillId)
                .filter(w -> w.getTenantId() == null || tenantId.equals(w.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
        return withItems(waybill);
    }

    @PatchMapping("/api/cargo/waybills/{waybillId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems update(@PathVariable UUID waybillId, @Valid @RequestBody UpdateWaybillRequest request) {
        return withItems(cargoWaybillService.update(waybillId, TenantContext.require(), request));
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/dispatch")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems dispatch(@PathVariable UUID waybillId) {
        return withItems(cargoWaybillService.dispatch(waybillId, TenantContext.require()));
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/arrive")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems arrive(@PathVariable UUID waybillId) {
        return withItems(cargoWaybillService.arrive(waybillId, TenantContext.require()));
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/collect")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems collect(@PathVariable UUID waybillId, @Valid @RequestBody CollectWaybillRequest request) {
        return withItems(cargoWaybillService.collect(waybillId, TenantContext.require(), request.presentedIdNumber()));
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/cancel")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybillCancellation cancel(
            @PathVariable UUID waybillId,
            @RequestBody(required = false) CancelWaybillRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID cancelledByUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;
        return cargoWaybillService.cancel(waybillId, tenantId, cancelledByUserId, reason);
    }

    /**
     * Public, unauthenticated - permitAll()'d ahead of /api/** in
     * SecurityConfig. `phone` is a required second factor since
     * waybillNumber alone isn't proof of a right to view this shipment -
     * see WaybillTrackingView's javadoc for what is/isn't exposed here.
     */
    @GetMapping("/api/cargo/track/{waybillNumber}")
    public WaybillTrackingView track(@PathVariable String waybillNumber, @RequestParam String phone) {
        return cargoWaybillService.track(waybillNumber, phone);
    }

    // --- Below: a logged-in customer's own shipment history - the natural
    // companion to BookingController's /api/my-bookings, added so a
    // registered customer can see waybills tied to a booking they made, not
    // just their trips. Ownership-scoped through the attached booking (see
    // CargoWaybillRepository) - a waybill with no bookingId never shows up
    // here for anyone, matching how waybills are staff-created in v1.

    @GetMapping("/api/my-shipments")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<WaybillWithItems> myShipments(@AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return cargoWaybillRepository.findAllOwnedByCustomer(customerUserId).stream()
                .map(this::withItems)
                .toList();
    }

    @GetMapping("/api/my-shipments/{waybillId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public WaybillWithItems myShipment(@PathVariable UUID waybillId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        CargoWaybill waybill = cargoWaybillRepository.findByIdOwnedByCustomer(waybillId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
        return withItems(waybill);
    }

    /**
     * Customer self-service shipment request - no trip picked yet, no
     * pricing, status "requested". Staff reviews/prices/issues it via
     * confirmAndIssue below. See CargoWaybillService.requestShipment.
     */
    @PostMapping("/api/my-shipments")
    @PreAuthorize("hasRole('CUSTOMER')")
    public WaybillWithItems requestShipment(@Valid @RequestBody CreateShipmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return withItems(cargoWaybillService.requestShipment(request, customerUserId));
    }

    /**
     * Staff-facing inbox of "requested" waybills awaiting review - see
     * CargoWaybillRepository.findAllByStatusAndTenantIdIsNull's javadoc for
     * why this is visible to any operator's staff, not tenant-scoped like
     * every other staff endpoint here.
     */
    @GetMapping("/api/cargo/requests")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<WaybillWithItems> pendingRequests() {
        return cargoWaybillRepository.findAllByStatusAndTenantIdIsNull("requested").stream()
                .map(this::withItems)
                .toList();
    }

    /** See CargoWaybillService.confirmAndIssue. */
    @PostMapping("/api/cargo/waybills/{waybillId}/confirm-and-issue")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public WaybillWithItems confirmAndIssue(
            @PathVariable UUID waybillId,
            @Valid @RequestBody ConfirmAndIssueWaybillRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID issuedByUserId = currentUserService.resolveInternalUserId(jwt);
        return withItems(cargoWaybillService.confirmAndIssue(waybillId, tenantId, issuedByUserId, request));
    }

    @ExceptionHandler(ProhibitedItemException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleProhibitedItem(ProhibitedItemException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoCargoRateConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleNoRateConfigured(NoCargoRateConfiguredException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidWaybillItemsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidItems(InvalidWaybillItemsException e) {
        return e.getMessage();
    }

    @ExceptionHandler(RequestNotIssuableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleRequestNotIssuable(RequestNotIssuableException e) {
        return e.getMessage();
    }

    @ExceptionHandler(BookingTripMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBookingTripMismatch(BookingTripMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidWaybillStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidWaybillStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(WaybillAlreadyCancelledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyCancelled(WaybillAlreadyCancelledException e) {
        return e.getMessage();
    }

    @ExceptionHandler(ConsigneeIdentityMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleIdentityMismatch(ConsigneeIdentityMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
