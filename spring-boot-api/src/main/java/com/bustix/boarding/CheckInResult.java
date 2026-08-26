package com.bustix.boarding;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for POST .../check-in - composed rather than the raw BookingSeat
 * entity, same reasoning as BookedSeatView: BookingSeat's embedded
 * {bookingId, seatId} id isn't something a client should have to unpack,
 * and seatNo lives on the seats table, not booking_seats.
 */
public record CheckInResult(
    UUID seatId,
    String seatNo,
    String passengerName,
    String boardingStatus,
    Instant boardedAt
) {
}
