package com.bustix.cargo;

import java.time.Instant;

/**
 * The public, unauthenticated track-by-number response (see decision 9 in
 * my-notes/cargo_logistics_scope_v1.md). Deliberately narrow: no money
 * fields, no ID numbers, no consignor/consignee names - this path has no
 * login and no tenant check beyond the phone match in
 * CargoWaybillService.track, so nothing sensitive is exposed to "anyone who
 * knows the waybill number and a phone number on the shipment."
 */
public record WaybillTrackingView(
    String waybillNumber,
    String status,
    Instant issuedAt,
    Instant dispatchedAt,
    Instant arrivedAt,
    Instant collectedAt,
    String origin,
    String destination,
    Instant departureAt,
    /** Operator support contact from operator_settings - null when not provided. The operator's own public contact, not sensitive. */
    String operatorSupportPhone,
    String operatorSupportEmail
) {
}
