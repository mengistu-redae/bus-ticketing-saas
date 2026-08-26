package com.bustix.refund;

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

@Entity
@Table(name = "cancellations")
@Getter
@Setter
public class Cancellation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** Agent or operator_admin - both allowed, see CancellationController. */
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    private String reason;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount;

    @Column(name = "refunded_at", nullable = false)
    private Instant refundedAt = Instant.now();
}
