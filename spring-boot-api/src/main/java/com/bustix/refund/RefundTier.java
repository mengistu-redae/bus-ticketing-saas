package com.bustix.refund;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * One tier of an operator's refund policy, e.g. "24h+ notice -> 100% back".
 * Field names are snake_case in storage (see the JSON example on
 * refund_policies in V1__init.sql), hence the explicit @JsonProperty rather
 * than relying on whatever naming strategy the app's ObjectMapper happens
 * to have configured.
 *
 * The @Min/@Max constraints only bite when this record is used as (part of)
 * a validated @RequestBody, i.e. RefundPolicyController's create/update -
 * they're inert when Jackson deserializes a tier straight out of an
 * existing refund_policies.rules JSON column (RefundCalculator.parseTiers),
 * so adding them here doesn't change how the DB-read path behaves.
 */
public record RefundTier(
    @JsonProperty("cutoff_hours") @Min(0) int cutoffHours,
    @JsonProperty("refund_percent") @Min(0) @Max(100) int refundPercent
) {
}
