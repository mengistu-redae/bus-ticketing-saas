package com.bustix.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    // Staff-scoped.
    List<Trip> findAllByTenantId(UUID tenantId);

    Optional<Trip> findByIdAndTenantId(UUID id, UUID tenantId);

    // Customer-scoped cross-tenant search: trips for any route, departing
    // after "now", across every operator.
    List<Trip> findAllByRouteIdAndDepartureAtAfter(UUID routeId, Instant after);

    // ---- dashboard aggregates (com.bustix.dashboard.DashboardService) ----

    long countByTenantIdAndStatusAndDepartureAtAfter(UUID tenantId, String status, Instant after);

    List<Trip> findTop8ByTenantIdAndStatusAndDepartureAtAfterOrderByDepartureAtAsc(
            UUID tenantId, String status, Instant after);

    /** Agent dashboard: departures leaving within the next 24h. */
    List<Trip> findByTenantIdAndStatusAndDepartureAtBetweenOrderByDepartureAtAsc(
            UUID tenantId, String status, Instant from, Instant to);

    long countByStatusAndDepartureAtAfter(String status, Instant after); // platform_admin (cross-tenant)

    /**
     * Boarding Gate State Machine's "Gate Lockout" (see
     * my-notes/ethiopian_bus_system_specs.md section 4.1) - bulk-flips any
     * trip whose departure has passed to boarding_closed, driven by
     * TripLifecycleScheduler. Purely for visibility (a boarding-closed
     * trip disappearing from marketplace search, which already filters on
     * status='scheduled') - BoardingService.checkIn never relies on this
     * having already run, since a polling job's lag must never be the
     * actual real-time gate decision.
     */
    @Modifying
    @Query("UPDATE Trip t SET t.status = 'boarding_closed' WHERE t.status = 'scheduled' AND t.departureAt <= :now")
    int closeBoardingForDepartedTrips(@Param("now") Instant now);
}
