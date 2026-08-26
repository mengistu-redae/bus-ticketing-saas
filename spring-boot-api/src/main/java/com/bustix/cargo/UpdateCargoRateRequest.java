package com.bustix.cargo;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Partial update - only non-null fields are applied (CargoRateController).
 * routeId is deliberately not editable here, same reasoning as
 * UpdateRefundPolicyRequest: changing which route a rate applies to is a
 * different rate, not a correction to this one - delete and recreate
 * instead.
 */
public record UpdateCargoRateRequest(
    @DecimalMin(value = "0.0", message = "freeWeightThresholdKg must not be negative") BigDecimal freeWeightThresholdKg,
    @DecimalMin(value = "0.0", message = "baseFreightCharge must not be negative") BigDecimal baseFreightCharge,
    @DecimalMin(value = "0.0", message = "surchargePerKg must not be negative") BigDecimal surchargePerKg,
    @DecimalMin(value = "0.0", message = "handlingFee must not be negative") BigDecimal handlingFee
) {
}
