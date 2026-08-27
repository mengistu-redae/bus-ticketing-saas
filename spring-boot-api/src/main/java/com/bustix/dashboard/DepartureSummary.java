package com.bustix.dashboard;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the "upcoming departures" / "occupancy" lists on the
 * operator/agent dashboards. {@code routeName} is resolved from the trip's
 * route in DashboardService (GET /api/fleet/trips returns bare Trip rows, no
 * denormalized name). {@code seatsBooked}/{@code capacity} come from two
 * bounded seat counts per trip; {@code rate} is their ratio (0..1), used to
 * rank the occupancy leaderboard.
 */
public record DepartureSummary(
        UUID tripId,
        String routeName,
        Instant departureAt,
        long seatsBooked,
        long capacity,
        double rate) {
}
