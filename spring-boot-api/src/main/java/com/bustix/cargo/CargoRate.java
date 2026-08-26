package com.bustix.cargo;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Operator-configurable freight pricing - shaped exactly like RefundPolicy
 * (route_id NULL = operator-wide default, a specific route_id overrides it
 * for that route only), reusing that same mental model rather than
 * inventing a second one. See my-notes/cargo_logistics_scope_v1.md decision
 * 3/10 for why this exists and why free_weight_threshold_kg lives here
 * instead of being a hardcoded "30".
 */
@Entity
@Table(name = "cargo_rates")
@Getter
@Setter
public class CargoRate extends BaseTenantEntity {

    /** NULL = operator-wide default. */
    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "free_weight_threshold_kg", nullable = false)
    private BigDecimal freeWeightThresholdKg = new BigDecimal("30.00");

    @Column(name = "base_freight_charge", nullable = false)
    private BigDecimal baseFreightCharge;

    @Column(name = "surcharge_per_kg", nullable = false)
    private BigDecimal surchargePerKg = new BigDecimal("10.00");

    @Column(name = "handling_fee", nullable = false)
    private BigDecimal handlingFee = new BigDecimal("50.00");
}
