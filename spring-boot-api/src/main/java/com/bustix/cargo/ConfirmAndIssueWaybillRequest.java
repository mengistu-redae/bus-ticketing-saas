package com.bustix.cargo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Staff review of a "requested" waybill (POST /api/cargo/waybills/{id}/
 * confirm-and-issue): assigns the real trip (and therefore tenant/
 * operator - the first point a customer request gets one), optionally
 * corrects the consignee ID and/or re-weighs the items after physically
 * inspecting the shipment, computes real pricing, and flips
 * requested -> issued. See CargoWaybillService.confirmAndIssue.
 */
public record ConfirmAndIssueWaybillRequest(
    @NotNull UUID tripId,
    /** Overrides the customer-supplied value if given; otherwise whatever's already on the waybill must be non-blank. */
    String consigneeIdNumber,
    /** Overrides the customer-declared items if given (same replace-the-whole-set semantics as UpdateWaybillRequest.items); otherwise the customer's own declared items are used as-is. */
    @Valid List<CreateWaybillRequest.ItemRequest> items
) {
}
