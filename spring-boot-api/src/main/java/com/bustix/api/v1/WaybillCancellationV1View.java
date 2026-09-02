package com.bustix.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of {@code POST /v1/waybills/{id}/cancel} - allowed pre-dispatch
 * only. The refund is computed off the operator's {@code refund_policies}
 * against the waybill's total cost, same engine as a booking cancellation.
 */
public record WaybillCancellationV1View(
    UUID waybillId,
    String waybillNumber,
    String status,
    BigDecimal refundAmount,
    Instant cancelledAt
) {
}
