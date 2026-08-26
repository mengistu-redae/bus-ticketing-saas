package com.bustix.cargo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * routeId is nullable on purpose - omit it to set/replace the operator-wide
 * default, same convention as CreateRefundPolicyRequest. The other three
 * fields fall back to CargoRate's own entity defaults (matching the BRD's
 * own numbers) when omitted, so an operator only has to specify what they
 * actually want to override.
 */
public record CreateCargoRateRequest(
    UUID routeId,
    @DecimalMin(value = "0.0", message = "freeWeightThresholdKg must not be negative") BigDecimal freeWeightThresholdKg,
    @NotNull @DecimalMin(value = "0.0", inclusive = true, message = "baseFreightCharge must not be negative") BigDecimal baseFreightCharge,
    @DecimalMin(value = "0.0", message = "surchargePerKg must not be negative") BigDecimal surchargePerKg,
    @DecimalMin(value = "0.0", message = "handlingFee must not be negative") BigDecimal handlingFee
) {
}
