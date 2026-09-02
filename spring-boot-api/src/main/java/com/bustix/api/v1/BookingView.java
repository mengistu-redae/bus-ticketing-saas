package com.bustix.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A booking as a partner sees it - a stable subset of the internal
 * {@code Booking} entity. No {@code idempotencyKey} or user-id columns.
 * Seats are a separate call ({@code GET /v1/bookings/{id}/seats}) so a list
 * response isn't an N+1.
 */
public record BookingView(
    UUID id,
    String bookingRef,
    String ticketNumber,
    UUID tripId,
    UUID operatorId,
    String channel,
    String status,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal rescheduleFee,
    BigDecimal totalAmount,
    Instant createdAt
) {
}
