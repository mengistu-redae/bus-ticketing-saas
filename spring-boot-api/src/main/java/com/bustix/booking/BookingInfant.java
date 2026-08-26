package com.bustix.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An infant (age < 3, see my-notes/ethiopian_bus_system_specs.md section
 * 4.1) riding on the lap of the adult seated at (bookingId, seatId) -
 * referenced there via a composite FK to booking_seats, not to a seats row
 * of its own. Seats are finite (generated from the bus's capacity at trip
 * creation); a lap-sitting infant riding with a paying adult must not
 * consume one of those slots or reduce the trip's sellable seat count, so
 * this table exists specifically to keep an infant off the seats/
 * booking_seats tables while still recording who they are. Always free -
 * there is no price column here, unlike BookingSeat.
 */
@Entity
@Table(name = "booking_infants")
@Getter
@Setter
public class BookingInfant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** The adult's seat this infant rides with - see the class javadoc for why this isn't the infant's own seat. */
    @Column(name = "seat_id", nullable = false)
    private UUID seatId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int age;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
