package com.bustix.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment collected against a booking. Schema has existed since V1 (cash
 * today, a real gateway later - see the comment on `payments` in
 * V1__init.sql) but nothing wrote to it until PaymentController. No
 * tenant_id of its own - scoped via booking_id, same shape as
 * BookingSeat/Cancellation/Notification; PaymentController resolves the
 * tenant check through the booking.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** cash today, gateway later. */
    @Column(nullable = false)
    private String method = "cash";

    @Column(nullable = false)
    private BigDecimal amount;

    /** Reference for non-cash methods (mobile money/card transaction id). Optional - cash has none. */
    @Column(name = "transaction_id")
    private String transactionId;

    /** Whichever staff member (agent/operator_admin) recorded the payment. */
    @Column(name = "collected_by")
    private UUID collectedBy;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt = Instant.now();
}
