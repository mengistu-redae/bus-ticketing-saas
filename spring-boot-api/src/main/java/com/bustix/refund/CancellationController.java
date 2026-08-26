package com.bustix.refund;

import com.bustix.tenant.TenantContext;
import com.bustix.user.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Two cancellation paths, kept as separate endpoints rather than one path
 * branching on role, because their booking lookups are scoped completely
 * differently (see CancellationService's javadoc on each):
 *  - staff (agent/operator_admin), tenant-scoped via TenantContext - the
 *    original v1 path, see the "cancelled_by agent or operator_admin, both
 *    allowed" comment on the cancellations table in V1__init.sql (now
 *    slightly stale: cancelled_by can be a customer's own app_user id too,
 *    see cancelMyBooking below - not worth a migration just for a comment).
 *  - customer self-service, ownership-scoped via customerUserId. Added
 *    2026-08-23 - previously a known gap (CLAUDE.md called this out as
 *    unbuilt: "Don't open POST /api/bookings/{id}/cancel to CUSTOMER; its
 *    tenant-scoped booking lookup assumes a staff token").
 */
@RestController
public class CancellationController {

    private final CancellationService cancellationService;
    private final CurrentUserService currentUserService;

    public CancellationController(CancellationService cancellationService, CurrentUserService currentUserService) {
        this.cancellationService = cancellationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Cancellation cancelBooking(
            @PathVariable UUID bookingId,
            @RequestBody(required = false) CancelBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // require(), not get(): both AGENT and OPERATOR_ADMIN are staff
        // roles that always carry an org claim - see TenantContext.require's
        // javadoc for exactly this case.
        UUID tenantId = TenantContext.require();
        UUID cancelledByUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;

        return cancellationService.cancel(bookingId, tenantId, cancelledByUserId, reason);
    }

    @PostMapping("/api/my-bookings/{bookingId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Cancellation cancelMyBooking(
            @PathVariable UUID bookingId,
            @RequestBody(required = false) CancelBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;

        return cancellationService.cancelAsCustomer(bookingId, customerUserId, reason);
    }

    @ExceptionHandler(BookingAlreadyCancelledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyCancelled(BookingAlreadyCancelledException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
