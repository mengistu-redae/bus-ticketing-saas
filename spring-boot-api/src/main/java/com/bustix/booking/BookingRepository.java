package com.bustix.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByTenantId(UUID tenantId);

    // Backs the trip-time-change notification cascade (TripUpdateService):
    // every still-active booking on a trip whose departure/arrival was just
    // edited. Not tenant-scoped in the signature because the caller already
    // resolved the trip tenant-scoped and passes its own tripId.
    List<Booking> findAllByTripIdAndStatus(UUID tripId, String status);

    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Booking> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    // Ownership-scoped, not tenant-scoped: backs customer self-service
    // cancellation, where the caller has no tenant (see TenantContext's
    // javadoc) - a customer can only ever act on their own booking.
    Optional<Booking> findByIdAndCustomerUserId(UUID id, UUID customerUserId);

    // Backs GET /api/my-bookings - the list-my-own-bookings counterpart to
    // the ownership-scoped lookup above.
    List<Booking> findAllByCustomerUserId(UUID customerUserId);

    // Used by TicketNumberGenerator's uniqueness check before assigning a
    // freshly generated ticket_number/booking_ref.
    boolean existsByTicketNumber(String ticketNumber);

    boolean existsByBookingRef(String bookingRef);

    // Backs the public guest track-by-ref endpoint
    // (BookingService.trackByRefAndPhone) - not tenant- or
    // customer-scoped, since the caller has neither; the phone match done
    // by the caller is what stands in for an ownership check here, same as
    // CargoWaybillRepository.findByWaybillNumber.
    Optional<Booking> findByBookingRef(String bookingRef);

    // ---- dashboard aggregates (com.bustix.dashboard.DashboardService) ----
    // Every tenant-scoped finder here takes tenantId explicitly, same
    // convention as the rest of this repository; the un-scoped ones back the
    // cross-tenant platform_admin dashboard only.

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant after);

    long countByTenantIdAndStatusAndCreatedAtAfter(UUID tenantId, String status, Instant after);

    /** Agent dashboard: bookings this agent personally took at the counter. */
    long countByTenantIdAndAgentUserIdAndCreatedAtAfter(UUID tenantId, UUID agentUserId, Instant after);

    List<Booking> findTop8ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.tenantId = :tenantId AND b.status = 'confirmed' AND b.createdAt >= :since")
    BigDecimal sumConfirmedRevenueSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    // platform_admin (cross-tenant):

    long countByCreatedAtAfter(Instant after);

    long countByStatusAndCreatedAtAfter(String status, Instant after);

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.status = 'confirmed' AND b.createdAt >= :since")
    BigDecimal sumAllConfirmedRevenueSince(@Param("since") Instant since);

    /** Rows: [tenantId (UUID), bookingCount (long), revenue (BigDecimal)] - DashboardService maps + resolves names. */
    @Query("SELECT b.tenantId, COUNT(b), COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.status = 'confirmed' AND b.createdAt >= :since "
            + "GROUP BY b.tenantId ORDER BY COUNT(b) DESC")
    List<Object[]> topOperatorsByBookingsSince(@Param("since") Instant since);

    // ---- dashboards v2: analytics (period-over-period deltas, daily series,
    // breakdowns, top routes) ----

    /** Prior-window count for a period-over-period delta - half-open [start, end). */
    long countByTenantIdAndCreatedAtBetween(UUID tenantId, Instant start, Instant end);

    long countByCreatedAtBetween(Instant start, Instant end); // platform

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.tenantId = :tenantId "
            + "AND b.status = 'confirmed' AND b.createdAt >= :start AND b.createdAt < :end")
    BigDecimal sumConfirmedRevenueBetween(@Param("tenantId") UUID tenantId,
                                          @Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.status = 'confirmed' AND b.createdAt >= :start AND b.createdAt < :end")
    BigDecimal sumAllConfirmedRevenueBetween(@Param("start") Instant start, @Param("end") Instant end);

    // Daily buckets - native, since JPQL has no date_trunc. Truncated in UTC
    // ('created_at AT TIME ZONE UTC') to match how the rest of the app treats
    // time (hibernate.jdbc.time_zone: UTC, and v1's "today" is a UTC boundary).
    // Rows: [day 'YYYY-MM-DD' (String), bookings (Number), revenue (Number),
    // cancellations (Number)]. Zero days are filled in by DashboardService.
    @Query(value = """
            SELECT to_char(date_trunc('day', b.created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day,
                   count(*) AS bookings,
                   coalesce(sum(b.total_amount) FILTER (WHERE b.status = 'confirmed'), 0) AS revenue,
                   count(*) FILTER (WHERE b.status = 'cancelled') AS cancellations
            FROM bookings b
            WHERE b.tenant_id = :tenantId AND b.created_at >= :since
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> dailySeries(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query(value = """
            SELECT to_char(date_trunc('day', b.created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day,
                   count(*) AS bookings,
                   coalesce(sum(b.total_amount) FILTER (WHERE b.status = 'confirmed'), 0) AS revenue,
                   count(*) FILTER (WHERE b.status = 'cancelled') AS cancellations
            FROM bookings b
            WHERE b.created_at >= :since
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> dailySeriesAllTenants(@Param("since") Instant since);

    /** Agent card sparkline: this agent's own counter bookings per day. Rows: [day (String), count (Number)]. */
    @Query(value = """
            SELECT to_char(date_trunc('day', b.created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day, count(*)
            FROM bookings b
            WHERE b.tenant_id = :tenantId AND b.agent_user_id = :agentUserId AND b.created_at >= :since
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> agentDailyBookingSeries(@Param("tenantId") UUID tenantId,
                                           @Param("agentUserId") UUID agentUserId, @Param("since") Instant since);

    // Top routes by confirmed revenue - native join, since bookings->trips->routes
    // are plain UUID FK columns here, not mapped JPA relations. Rows:
    // [routeId (UUID), origin (String), destination (String), bookings (Number), revenue (Number)].
    @Query(value = """
            SELECT r.id, r.origin, r.destination, count(b.id), coalesce(sum(b.total_amount), 0)
            FROM bookings b
            JOIN trips t ON b.trip_id = t.id
            JOIN routes r ON t.route_id = r.id
            WHERE b.tenant_id = :tenantId AND b.status = 'confirmed' AND b.created_at >= :since
            GROUP BY r.id, r.origin, r.destination
            ORDER BY 5 DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> topRoutesByRevenue(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query(value = """
            SELECT r.id, r.origin, r.destination, count(b.id), coalesce(sum(b.total_amount), 0)
            FROM bookings b
            JOIN trips t ON b.trip_id = t.id
            JOIN routes r ON t.route_id = r.id
            WHERE b.status = 'confirmed' AND b.created_at >= :since
            GROUP BY r.id, r.origin, r.destination
            ORDER BY 5 DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> topRoutesByRevenueAllTenants(@Param("since") Instant since);

    // Categorical breakdowns - JPQL is fine here (plain GROUP BY on a column).
    // Rows: [key (String), count (long), revenue (BigDecimal)].
    @Query("SELECT b.channel, COUNT(b), COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.tenantId = :tenantId AND b.createdAt >= :since GROUP BY b.channel")
    List<Object[]> channelBreakdown(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query("SELECT b.status, COUNT(b), COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.tenantId = :tenantId AND b.createdAt >= :since GROUP BY b.status")
    List<Object[]> statusBreakdown(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query("SELECT b.channel, COUNT(b), COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.createdAt >= :since GROUP BY b.channel")
    List<Object[]> channelBreakdownAllTenants(@Param("since") Instant since);

    @Query("SELECT b.status, COUNT(b), COALESCE(SUM(b.totalAmount), 0) FROM Booking b "
            + "WHERE b.createdAt >= :since GROUP BY b.status")
    List<Object[]> statusBreakdownAllTenants(@Param("since") Instant since);
}
