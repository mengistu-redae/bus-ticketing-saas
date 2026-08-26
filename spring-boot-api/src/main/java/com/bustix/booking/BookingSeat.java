package com.bustix.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_seats")
@Getter
@Setter
public class BookingSeat {

    @EmbeddedId
    private Id id;

    @Column(nullable = false)
    private BigDecimal price;

    /** Named passenger for this seat - a booking can cover several seats, each for a different passenger. */
    @Column(name = "passenger_name")
    private String passengerName;

    @Column(name = "passenger_phone")
    private String passengerPhone;

    /** ID/passport number - what's actually checked at boarding, not always collected at booking time. */
    @Column(name = "passenger_id_number")
    private String passengerIdNumber;

    /** Which kind of document passengerIdNumber is from - see IdentityDocumentType. Stored as its enum name. */
    @Column(name = "passenger_id_type")
    @Enumerated(EnumType.STRING)
    private IdentityDocumentType passengerIdType;

    /**
     * The seated (adult) passenger's age - optional metadata, not itself
     * pricing-relevant (v1 has one flat adult fare regardless of exact
     * age). An accompanying infant (age < 3) is a separate BookingInfant
     * row, not its own seat - see that class's javadoc.
     */
    @Column(name = "passenger_age")
    private Integer passengerAge;

    /**
     * Boarding Gate State Machine (my-notes/ethiopian_bus_system_specs.md
     * section 4.1) - "not_boarded" or "boarded". Flipped by
     * BoardingService.checkIn after validating the presented ID against
     * passengerIdNumber above; blocked once the trip's departure passes
     * (see BoardingService/TripLifecycleScheduler).
     */
    @Column(name = "boarding_status", nullable = false)
    private String boardingStatus = "not_boarded";

    @Column(name = "boarded_at")
    private Instant boardedAt;

    @Embeddable
    @Getter
    @Setter
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "booking_id")
        private UUID bookingId;

        @Column(name = "seat_id")
        private UUID seatId;
    }
}
