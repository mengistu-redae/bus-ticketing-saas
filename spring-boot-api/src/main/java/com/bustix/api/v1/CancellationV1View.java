package com.bustix.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of {@code POST /v1/bookings/{id}/cancel}: the booking is now
 * cancelled, its seats are freed, and this refund was computed from the
 * operator's refund policy and the notice period.
 */
public record CancellationV1View(
    UUID bookingId,
    String bookingRef,
    String status,
    BigDecimal refundAmount,
    Instant cancelledAt
) {
}
