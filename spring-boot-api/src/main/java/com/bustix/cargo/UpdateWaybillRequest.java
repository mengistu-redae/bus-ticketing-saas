package com.bustix.cargo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Partial update (CargoWaybillController.update) - only non-null fields are
 * applied, same convention as every other PATCH in this app. `paymentStatus`
 * is applicable at any status; every other field here is a
 * physical-shipment field and only applicable while status = "issued" -
 * CargoWaybillService.update throws InvalidWaybillStatusException (409) if
 * a caller tries to change one of those after dispatch (decision 11 in
 * my-notes/cargo_logistics_scope_v1.md), rather than silently ignoring it
 * the way an unset field is.
 *
 * `items`, when non-null, replaces the *entire* item set (delete-all-then-
 * reinsert) - there's no per-item patch semantics, matching "physical
 * fields are frozen unless issued; re-declare the whole shipment before
 * dispatch if something was wrong." Deliberately not @NotEmpty here (unlike
 * CreateWaybillRequest.items) - null means "leave items alone" for this
 * PATCH, so the annotation can't distinguish "omitted" from "must be
 * non-empty"; CargoWaybillService.update rejects an explicitly-empty list
 * itself (InvalidWaybillItemsException, 400).
 */
public record UpdateWaybillRequest(
    String consignorName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    String consignorPhone,
    String consignorIdNumber,

    String consigneeName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    String consigneePhone,
    String consigneeIdNumber,

    /** Optional shipment-level summary. */
    String description,
    @Valid List<CreateWaybillRequest.ItemRequest> items,

    @Pattern(regexp = "^(unpaid|paid|collect_on_delivery)$", message = "paymentStatus must be one of: unpaid, paid, collect_on_delivery")
    String paymentStatus
) {
}
