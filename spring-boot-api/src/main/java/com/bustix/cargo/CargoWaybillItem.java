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

/**
 * One line item within a multi-item shipment - see cargo_waybill_items in
 * V9. Plain @Entity (not BaseTenantEntity), scoped via waybillId rather
 * than its own tenant_id, same shape as CargoWaybillCancellation - a
 * waybill's tenant scoping is the source of truth, items are always looked
 * up through a waybill that's already been access-checked.
 */
@Entity
@Table(name = "cargo_waybill_items")
@Getter
@Setter
public class CargoWaybillItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "declared_value")
    private BigDecimal declaredValue;

    @Column(name = "gross_weight_kg", nullable = false)
    private BigDecimal grossWeightKg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
