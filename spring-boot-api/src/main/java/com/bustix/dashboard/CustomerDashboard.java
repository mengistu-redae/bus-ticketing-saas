package com.bustix.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/my-dashboard payload - ownership-scoped to the signed-in customer
 * (customer_user_id), the same access shape as GET /api/my-bookings /
 * /api/my-shipments. Backs the dashboard block on the customer Home page.
 */
public record CustomerDashboard(
        Counts counts,
        List<UpcomingTrip> upcomingTrips,
        List<ShipmentSummary> activeShipments) {

    public record Counts(long upcoming, long past, long cancelled) {
    }

    public record UpcomingTrip(
            UUID bookingId,
            String bookingRef,
            String routeName,
            Instant departureAt,
            String status) {
    }

    public record ShipmentSummary(
            UUID waybillId,
            String waybillNumber,
            String status,
            String routeName,
            Instant departureAt) {
    }
}
