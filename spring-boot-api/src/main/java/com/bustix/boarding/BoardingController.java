package com.bustix.boarding;

import com.bustix.refund.BookingAlreadyCancelledException;
import com.bustix.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Gate check-in - staff-only (a passenger doesn't check themselves in; an
 * agent/operator_admin scans/reads their ID at the terminal). Tenant-scoped
 * through the booking, same pattern PaymentController/CancellationController
 * use.
 */
@RestController
public class BoardingController {

    private final BoardingService boardingService;

    public BoardingController(BoardingService boardingService) {
        this.boardingService = boardingService;
    }

    @PostMapping("/api/bookings/{bookingId}/seats/{seatId}/check-in")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public CheckInResult checkIn(
            @PathVariable UUID bookingId,
            @PathVariable UUID seatId,
            @Valid @RequestBody CheckInRequest request) {
        return boardingService.checkIn(bookingId, seatId, TenantContext.require(), request.presentedIdNumber());
    }

    @ExceptionHandler(IdentityMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleIdentityMismatch(IdentityMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(BoardingClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleBoardingClosed(BoardingClosedException e) {
        return e.getMessage();
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
