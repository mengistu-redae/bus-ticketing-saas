package com.bustix.cargo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Customer self-service shipment request (POST /api/my-shipments) -
 * deliberately a separate DTO from CreateWaybillRequest (staff creation),
 * mirroring the existing CreateBookingRequest/CreateGuestBookingRequest
 * split for "different caller, different required shape" rather than one
 * DTO with more nullable fields bolted on.
 *
 * No tripId - the customer may not have picked a bus yet. No pricing is
 * computed here (see CargoWaybillService.requestShipment) - items are
 * customer-declared estimates, staff weighs/prices the real shipment at
 * confirm-and-issue time. consigneeIdNumber is optional here unlike
 * CreateWaybillRequest's required version - a request made ahead of time
 * may not have it on hand yet.
 */
public record CreateShipmentRequest(
    @NotBlank String consignorName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    @NotBlank String consignorPhone,
    String consignorIdNumber,

    @NotBlank String consigneeName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    @NotBlank String consigneePhone,
    String consigneeIdNumber,

    /** Optional shipment-level summary - items below carry the real per-item detail. */
    String description,
    @NotEmpty @Valid List<CreateWaybillRequest.ItemRequest> items
) {
}
