package com.bustix.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One seat on a booking, for GET /api/my-bookings/{id}/seats - joins
 * booking_seats (price paid) with seats (seat_no/seat_class) since neither
 * table alone has both. A composed response like TripSearchResult, not a
 * raw entity - BookingSeat's own shape (an embedded {bookingId, seatId} key)
 * isn't something a client should have to unpack.
 *
 * `infants` are this seat's accompanying lap-sitting infants (age < 3,
 * always free) - see BookingInfant's javadoc for why they're not their own
 * BookedSeatView entries. `boardingStatus`/`boardedAt` reflect
 * BoardingService.checkIn - see the Boarding Gate State Machine note.
 */
public record BookedSeatView(
    UUID seatId,
    String seatNo,
    String seatClass,
    BigDecimal price,
    String passengerName,
    String passengerPhone,
    String passengerIdNumber,
    IdentityDocumentType passengerIdType,
    Integer passengerAge,
    List<InfantView> infants,
    String boardingStatus,
    Instant boardedAt
) {
    public record InfantView(String name, int age) {
    }
}
