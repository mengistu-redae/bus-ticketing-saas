package com.bustix.dashboard;

import com.bustix.booking.Booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Narrow read shape for the "recent bookings" list on the operator/agent
 * dashboards - a subset of {@link Booking}'s fields, same purpose-built-view
 * role {@code BookedSeatView}/{@code TripSearchResult} play elsewhere in this
 * API. Deliberately no passenger PII (names/phones/ID numbers live on
 * booking_seats, not needed for an at-a-glance list).
 */
public record BookingSummary(
        UUID id,
        String bookingRef,
        String ticketNumber,
        String status,
        String channel,
        BigDecimal totalAmount,
        Instant createdAt) {

    static BookingSummary of(Booking b) {
        return new BookingSummary(
                b.getId(),
                b.getBookingRef(),
                b.getTicketNumber(),
                b.getStatus(),
                b.getChannel(),
                b.getTotalAmount(),
                b.getCreatedAt());
    }
}
