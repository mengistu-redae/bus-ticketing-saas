package com.bustix.cargo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Partial update (CargoWaybillController.update) - only non-null fields are
 * applied, same convention as every other PATCH in this app. `paymentStatus`
 * is applicable at any status; every other field here is a
 * physical-shipment field and only applicable while status = "issued" -
 * CargoWaybillService.update throws InvalidWaybillStatusException (409) if
 * a caller tries to change one of those after dispatch (decision 11 in
 * my-notes/cargo_logistics_scope_v1.md), rather than silently ignoring it
 * the way an unset field is.
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

    String description,
    @Min(1) Integer quantity,
    @DecimalMin(value = "0.0", message = "declaredValue must not be negative") BigDecimal declaredValue,
    @DecimalMin(value = "0.01", message = "grossWeightKg must be positive") BigDecimal grossWeightKg,

    @Pattern(regexp = "^(unpaid|paid|collect_on_delivery)$", message = "paymentStatus must be one of: unpaid, paid, collect_on_delivery")
    String paymentStatus
) {
}
