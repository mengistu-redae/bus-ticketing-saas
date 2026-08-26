package com.bustix.cargo;

import com.bustix.payment.CreatePaymentRequest;
import com.bustix.payment.Payment;
import com.bustix.payment.PaymentRepository;
import com.bustix.payment.UpdatePaymentRequest;
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
 * Payments collected against a cargo waybill - the freight counterpart to
 * PaymentController, kept as its own controller (not new methods on that
 * one) since PaymentController's @RequestMapping base path is hard-coded
 * to /api/bookings/{bookingId}/payments, a different resource nesting.
 * Reuses the same Payment entity/table (V10 added a nullable waybillId and
 * a DB CHECK enforcing exactly one of bookingId/waybillId), same
 * requireOwnedWaybill -> findOwnedPayment resolution shape as
 * PaymentController and CargoWaybillController.findOwnedWaybill/get. No
 * DELETE, same "a payment is a financial fact, not soft-deletable" rule
 * as PaymentController.
 */
@RestController
@RequestMapping("/api/cargo/waybills/{waybillId}/payments")
public class CargoPaymentController {

    private final PaymentRepository paymentRepository;
    private final CargoWaybillRepository cargoWaybillRepository;
    private final CurrentUserService currentUserService;

    public CargoPaymentController(
            PaymentRepository paymentRepository,
            CargoWaybillRepository cargoWaybillRepository,
            CurrentUserService currentUserService) {
        this.paymentRepository = paymentRepository;
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public List<Payment> list(@PathVariable UUID waybillId) {
        requireOwnedWaybill(waybillId);
        return paymentRepository.findAllByWaybillId(waybillId);
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Payment get(@PathVariable UUID waybillId, @PathVariable UUID paymentId) {
        requireOwnedWaybill(waybillId);
        return findOwnedPayment(waybillId, paymentId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT', 'OPERATOR_ADMIN')")
    public Payment create(
            @PathVariable UUID waybillId,
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requireOwnedWaybill(waybillId);

        Payment payment = new Payment();
        payment.setWaybillId(waybillId);
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
            @PathVariable UUID waybillId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody UpdatePaymentRequest request) {
        requireOwnedWaybill(waybillId);
        Payment payment = findOwnedPayment(waybillId, paymentId);

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

    private void requireOwnedWaybill(UUID waybillId) {
        cargoWaybillRepository.findByIdAndTenantId(waybillId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
    }

    private Payment findOwnedPayment(UUID waybillId, UUID paymentId) {
        return paymentRepository.findByIdAndWaybillId(paymentId, waybillId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
