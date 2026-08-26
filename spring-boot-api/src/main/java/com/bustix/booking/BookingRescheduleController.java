package com.bustix.booking;

import com.bustix.refund.BookingAlreadyCancelledException;
import com.bustix.tenant.TenantContext;
import com.bustix.user.CurrentUserService;
import jakarta.validation.Valid;
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
 * Rescheduling - two endpoints kept separate rather than one branching on
 * role, same reasoning as CancellationController: their booking lookups
 * are scoped completely differently (tenant vs. ownership).
 */
@RestController
public class BookingRescheduleController {

    private final BookingRescheduleService bookingRescheduleService;
    private final CurrentUserService currentUserService;

    public BookingRescheduleController(
            BookingRescheduleService bookingRescheduleService,
            CurrentUserService currentUserService) {
        this.bookingRescheduleService = bookingRescheduleService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/bookings/{bookingId}/reschedule")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Booking reschedule(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingUserId = currentUserService.resolveInternalUserId(jwt);
        return bookingRescheduleService.reschedule(bookingId, tenantId, request.newTripId(), request.newSeatId(), actingUserId);
    }

    @PostMapping("/api/my-bookings/{bookingId}/reschedule")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Booking rescheduleMyBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return bookingRescheduleService.rescheduleAsCustomer(bookingId, customerUserId, request.newTripId(), request.newSeatId());
    }

    @ExceptionHandler(SeatConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleSeatConflict(SeatConflictException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TooLateToRescheduleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleTooLate(TooLateToRescheduleException e) {
        return e.getMessage();
    }

    @ExceptionHandler(MultiSeatRescheduleNotSupportedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleMultiSeatNotSupported(MultiSeatRescheduleNotSupportedException e) {
        return e.getMessage();
    }

    @ExceptionHandler(BookingAlreadyCancelledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyCancelled(BookingAlreadyCancelledException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TenantMismatchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleTenantMismatch(TenantMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
