package com.bustix.cargo;

import java.util.List;

/**
 * Wraps a CargoWaybill with its line items for every read/write response
 * that needs both - see CargoWaybillController. CargoWaybill doesn't carry
 * a JPA relation to its items (this codebase uses plain UUID FKs + explicit
 * repository queries throughout, never mapped cross-entity relations), so
 * this is the purpose-built read shape that stitches the two together for
 * a client, same role WaybillTrackingView plays for the public track
 * endpoint.
 */
public record WaybillWithItems(CargoWaybill waybill, List<CargoWaybillItem> items) {
}
