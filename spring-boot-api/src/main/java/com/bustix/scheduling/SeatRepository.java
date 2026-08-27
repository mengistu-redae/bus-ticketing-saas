package com.bustix.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findAllByTripId(UUID tripId);

    Optional<Seat> findByIdAndTripId(UUID id, UUID tripId);

    long countByTripIdAndStatus(UUID tripId, String status);

    /** Total generated seats on a trip = its effective capacity (see DashboardService). */
    long countByTripId(UUID tripId);
}
