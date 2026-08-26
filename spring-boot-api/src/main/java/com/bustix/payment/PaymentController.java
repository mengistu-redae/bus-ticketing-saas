package com.bustix.payment;

import com.bustix.booking.BookingRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Records payments collected against a booking - the `payments` table has
 * existed since V1 (cash today, a real gateway later) but nothing wrote to
 * it until now. Staff-only (a customer doesn't "record" their own
 * self-service payment - v1 has no payment collection step in the
 * self_service channel at all, only counter). Tenant-scoped through the
 * booking, same pattern CancellationController uses: `payments` itself
 * carries no tenant_id, so the booking lookup below doubles as both "does
 * this booking exist" and "does it belong to my operator."
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/payments")
public class PaymentController {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final CurrentUserService currentUserService;

    public PaymentController(
            PaymentRepository paymentRepository,
            BookingRepository bookingRepository,
            CurrentUserService currentUserService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<Payment> list(@PathVariable UUID bookingId) {
        requireOwnedBooking(bookingId);
        return paymentRepository.findAllByBookingId(bookingId);
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Payment get(@PathVariable UUID bookingId, @PathVariable UUID paymentId) {
        requireOwnedBooking(bookingId);
        return findOwnedPayment(bookingId, paymentId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Payment create(
            @PathVariable UUID bookingId,
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requireOwnedBooking(bookingId);

        Payment payment = new Payment();
        payment.setBookingId(bookingId);
        if (request.method() != null && !request.method().isBlank()) {
            payment.setMethod(request.method());
        }
        payment.setAmount(request.amount());
        payment.setTransactionId(request.transactionId());
        payment.setCollectedBy(currentUserService.resolveInternalUserId(jwt));
        return paymentRepository.save(payment);
    }

    @PatchMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Payment update(
            @PathVariable UUID bookingId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody UpdatePaymentRequest request) {
        requireOwnedBooking(bookingId);
        Payment payment = findOwnedPayment(bookingId, paymentId);

        if (request.method() != null && !request.method().isBlank()) {
            payment.setMethod(request.method());
        }
        if (request.amount() != null) {
            payment.setAmount(request.amount());
        }
        if (request.transactionId() != null) {
            payment.setTransactionId(request.transactionId());
        }
        return paymentRepository.save(payment);
    }

    private void requireOwnedBooking(UUID bookingId) {
        bookingRepository.findByIdAndTenantId(bookingId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
    }

    private Payment findOwnedPayment(UUID bookingId, UUID paymentId) {
        return paymentRepository.findByIdAndBookingId(paymentId, bookingId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
