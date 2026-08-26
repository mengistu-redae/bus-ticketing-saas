package com.bustix.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit trail of one reschedule - the same role `cancellations` plays for
 * cancels (see CancellationService), a append-only record kept even though
 * `bookings` itself only carries the *current* trip/seat/fee. See
 * BookingRescheduleService for the rules (12h minimum notice, single-seat
 * bookings only in v1, a flat fee by channel).
 */
@Entity
@Table(name = "booking_reschedules")
@Getter
@Setter
public class BookingReschedule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "old_trip_id", nullable = false)
    private UUID oldTripId;

    @Column(name = "new_trip_id", nullable = false)
    private UUID newTripId;

    @Column(name = "old_seat_id", nullable = false)
    private UUID oldSeatId;

    @Column(name = "new_seat_id", nullable = false)
    private UUID newSeatId;

    @Column(nullable = false)
    private BigDecimal fee;

    /** Whichever customer/agent/operator_admin actually requested the reschedule. */
    @Column(name = "rescheduled_by")
    private UUID rescheduledBy;

    @Column(name = "rescheduled_at", nullable = false)
    private Instant rescheduledAt = Instant.now();
}
