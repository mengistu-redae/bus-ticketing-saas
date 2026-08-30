package com.bustix.scheduling;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findAllByTripId(UUID tripId);

    /**
     * {@code SELECT ... FOR UPDATE}. The only two callers -
     * {@code BookingWriter} and {@code BookingRescheduleService} - read a
     * seat, check {@code status = 'open'}, then flip it to {@code booked}.
     * The Redis seat lock (SeatLockService) fronts this for a fast 409 on
     * concurrent API requests, but the row lock is the correctness backstop:
     * without it, two transactions that both get past the Redis lock (its
     * TTL expiring mid-write, a Redis failover, a caller with locks disabled)
     * would each see {@code status = 'open'} and double-sell the seat, and
     * {@code booking_seats}'s {@code (booking_id, seat_id)} PK does not stop
     * the same seat landing in two bookings. Both callers already run inside
     * a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Seat> findByIdAndTripId(UUID id, UUID tripId);

    long countByTripIdAndStatus(UUID tripId, String status);

    /** Total generated seats on a trip = its effective capacity (see DashboardService). */
    long countByTripId(UUID tripId);
}
