package com.bustix.cargo;

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

/** Mirrors com.bustix.refund.Cancellation - same audit-trail role, scoped through waybill_id rather than a tenant_id of its own. */
@Entity
@Table(name = "cargo_waybill_cancellations")
@Getter
@Setter
public class CargoWaybillCancellation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    private String reason;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount;

    @Column(name = "refunded_at", nullable = false)
    private Instant refundedAt = Instant.now();
}
