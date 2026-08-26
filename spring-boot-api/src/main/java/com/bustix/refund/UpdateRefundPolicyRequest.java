package com.bustix.refund;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Only `tiers` is updatable - `route_id` is fixed at creation. Changing
 * which route a policy targets (including flipping it to/from the
 * operator-wide default) is a delete-and-recreate, not a PATCH: a nullable
 * routeId here couldn't distinguish "leave it alone" from "clear it to
 * null" without a wrapper type, and that ambiguity isn't worth it for a
 * field that's simpler to just not make editable.
 */
public record UpdateRefundPolicyRequest(
    List<@Valid RefundTier> tiers
) {
}
