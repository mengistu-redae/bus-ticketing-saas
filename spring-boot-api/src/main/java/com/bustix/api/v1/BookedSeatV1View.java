package com.bustix.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One seat on a booking, for {@code GET /v1/bookings/{id}/seats}. Joins
 * {@code booking_seats} (price paid, passenger) with {@code seats}
 * (seat number/class). {@code infants} are this seat's accompanying
 * lap-sitting infants (always free). {@code boardingStatus} reflects a
 * counter check-in ({@code null} until then).
 */
public record BookedSeatV1View(
    UUID seatId,
    String seatNo,
    String seatClass,
    BigDecimal price,
    String passengerName,
    String passengerPhone,
    String passengerIdNumber,
    String passengerIdType,
    Integer passengerAge,
    List<InfantView> infants,
    String boardingStatus,
    Instant boardedAt
) {
    public record InfantView(String name, int age) {
    }
}
