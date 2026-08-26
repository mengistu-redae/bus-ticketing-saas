package com.bustix.refund;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Structured tiers rather than a raw JSON string for `rules` - Jackson
 * serializes this to the same JSON shape RefundCalculator already parses
 * (see RefundTier), but rejects a malformed request at the API boundary
 * instead of only discovering a bad tier the next time a cancellation tries
 * to read it back.
 */
public record CreateRefundPolicyRequest(
    /** NULL = operator-wide default; a specific route overrides it for that route only. */
    UUID routeId,
    @NotEmpty List<@Valid RefundTier> tiers
) {
}
