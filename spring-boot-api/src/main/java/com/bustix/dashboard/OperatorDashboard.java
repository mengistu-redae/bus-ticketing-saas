package com.bustix.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/operator/dashboard?period=7d|30d|90d payload - tenant-scoped
 * analytics for an operator_admin. Revenue is SUM(bookings.total_amount) for
 * confirmed bookings only; the payments table is not summed for revenue (a
 * payment is a separate optional staff action, not auto-created - see
 * CLAUDE.md's "Known gaps") - it only feeds the paymentMethod breakdown.
 */
public record OperatorDashboard(
        String period,
        TrendCount bookings,
        TrendMoney revenue,
        Fleet fleet,
        Cargo cargo,
        DailySeries series,
        Breakdowns breakdowns,
        List<RouteStat> topRoutes,
        List<DepartureSummary> occupancy,
        List<BookingSummary> recentBookings,
        List<DepartureSummary> upcomingDepartures) {

    public record Fleet(long activeBuses, long activeRoutes, long upcomingTrips) {
    }

    public record Cargo(long activeWaybills, BigDecimal revenueCurrent, double revenueDeltaPct) {
    }

    public record Breakdowns(
            List<Breakdown> channel,
            List<Breakdown> status,
            List<Breakdown> cargoStatus,
            List<Breakdown> paymentMethod) {
    }
}
