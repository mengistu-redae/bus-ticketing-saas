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
    private final CurrentUserService currentUserService;

    public CargoWaybillController(
            CargoWaybillService cargoWaybillService,
            CargoWaybillRepository cargoWaybillRepository,
            CurrentUserService currentUserService) {
        this.cargoWaybillService = cargoWaybillService;
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/cargo/waybills")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill create(@Valid @RequestBody CreateWaybillRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID issuedByUserId = currentUserService.resolveInternalUserId(jwt);
        return cargoWaybillService.create(request, tenantId, issuedByUserId);
    }

    @GetMapping("/api/cargo/waybills")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<CargoWaybill> list(
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        if (tripId != null) {
            return cargoWaybillRepository.findAllByTenantIdAndTripId(tenantId, tripId);
        }
        if (status != null) {
            return cargoWaybillRepository.findAllByTenantIdAndStatus(tenantId, status);
        }
        return cargoWaybillRepository.findAllByTenantId(tenantId);
    }

    @GetMapping("/api/cargo/waybills/{waybillId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill get(@PathVariable UUID waybillId) {
        return cargoWaybillRepository.findByIdAndTenantId(waybillId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
    }

    @PatchMapping("/api/cargo/waybills/{waybillId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill update(@PathVariable UUID waybillId, @Valid @RequestBody UpdateWaybillRequest request) {
        return cargoWaybillService.update(waybillId, TenantContext.require(), request);
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/dispatch")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill dispatch(@PathVariable UUID waybillId) {
        return cargoWaybillService.dispatch(waybillId, TenantContext.require());
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/arrive")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill arrive(@PathVariable UUID waybillId) {
        return cargoWaybillService.arrive(waybillId, TenantContext.require());
    }

    @PostMapping("/api/cargo/waybills/{waybillId}/collect")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CargoWaybill collect(@PathVariable UUID waybillId, @Valid @RequestBody CollectWaybillRequest request) {
        return cargoWaybillService.collect(waybillId, TenantContext.require(), request.presentedIdNumber());
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
    public List<CargoWaybill> myShipments(@AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return cargoWaybillRepository.findAllByBookingCustomerUserId(customerUserId);
    }

    @GetMapping("/api/my-shipments/{waybillId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CargoWaybill myShipment(@PathVariable UUID waybillId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return cargoWaybillRepository.findByIdAndBookingCustomerUserId(waybillId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
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
