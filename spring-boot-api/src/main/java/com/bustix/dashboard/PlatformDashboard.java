package com.bustix.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/platform/dashboard?period=7d|30d|90d payload - cross-tenant
 * analytics for a platform_admin. Unlike every other dashboard method this
 * one never touches TenantContext (it stays empty for platform_admin tokens -
 * see TenantContext's javadoc).
 */
public record PlatformDashboard(
        String period,
        Operators operators,
        TrendCount bookings,
        TrendMoney revenue,
        long upcomingTrips,
        Cargo cargo,
        DailySeries series,
        Breakdowns breakdowns,
        List<RouteStat> topRoutes,
        List<TopOperator> topOperators,
        List<OperatorSummary> recentOperators) {

    public record Operators(long total, long active, long inactive) {
    }

    public record Cargo(long activeWaybills, long pendingRequests) {
    }

    public record Breakdowns(List<Breakdown> channel, List<Breakdown> status) {
    }

    public record TopOperator(UUID operatorId, String name, long bookings, BigDecimal revenue) {
    }

    public record OperatorSummary(UUID id, String name, String status, Instant createdAt) {
    }
}
