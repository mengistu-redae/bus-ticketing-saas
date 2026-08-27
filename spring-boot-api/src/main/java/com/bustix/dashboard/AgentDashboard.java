package com.bustix.dashboard;

import java.util.List;

/**
 * GET /api/agent/dashboard payload - a trimmed, counter-desk-focused view.
 * Tenant-scoped like the operator dashboard, but leads with the signed-in
 * agent's own counter activity (bookings where agent_user_id = this agent).
 * {@code sparkline14d} is a fixed 14-day daily count of this agent's counter
 * bookings, for the little trend line on the stat cards - no period selector
 * on this page.
 */
public record AgentDashboard(
        CounterCounts myCounterBookings,
        long operatorBookingsToday,
        long pendingCargoRequests,
        long activeWaybills,
        List<Long> sparkline14d,
        List<DepartureSummary> departuresNext24h,
        List<BookingSummary> recentBookings) {

    public record CounterCounts(long today, long last7d) {
    }
}
