package com.bustix.cargo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Staff-only (CargoWaybillController) - the counter physically weighs and
 * inspects the shipment before this is submitted. See
 * my-notes/cargo_logistics_scope_v1.md for why bookingId is optional and
 * consigneeIdNumber (unlike a passenger's passengerIdNumber) is required.
 *
 * A shipment is one or more line items (each its own description/quantity/
 * weight) - see CargoWaybillItem/V9. `description` here is an optional
 * shipment-level summary only; the real per-item detail is `items`.
 */
public record CreateWaybillRequest(
    @NotNull UUID tripId,
    /** Optional: accompanied excess baggage on an existing passenger booking on this same trip - see CargoWaybillService.create. */
    UUID bookingId,

    @NotBlank String consignorName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    @NotBlank String consignorPhone,
    String consignorIdNumber,

    @NotBlank String consigneeName,
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    @NotBlank String consigneePhone,
    @NotBlank String consigneeIdNumber,

    /** Optional shipment-level summary - items below carry the real per-item detail. */
    String description,
    @NotEmpty @Valid List<ItemRequest> items
) {

    /** One line item of a shipment - see CargoWaybillItem. */
    public record ItemRequest(
        @NotBlank String description,
        @Min(1) Integer quantity,
        @DecimalMin(value = "0.0", message = "declaredValue must not be negative") BigDecimal declaredValue,
        @NotNull @DecimalMin(value = "0.01", message = "grossWeightKg must be positive") BigDecimal grossWeightKg
    ) {
        /** Normalizes an omitted quantity to 1 rather than forcing every caller to null-check it. */
        public ItemRequest {
            if (quantity == null) {
                quantity = 1;
            }
        }
    }
}
