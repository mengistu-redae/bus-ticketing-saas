package com.bustix.fleet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    // Staff-scoped: managing one operator's own routes.
    List<Route> findAllByTenantId(UUID tenantId);

    Optional<Route> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Operator dashboard fleet count. */
    long countByTenantIdAndActiveTrue(UUID tenantId);

    // Customer-scoped: deliberately NOT tenant-filtered - this is the
    // marketplace search across every operator on the platform. tenant_id is
    // just a normal returned column here, not a filter.
    //
    // IgnoreCase on both fields - found live: a customer typing "addis
    // ababa" (any casing other than exactly how the operator entered it,
    // e.g. "Addis Ababa") got zero results with no indication why, since
    // this was originally an exact-match query. Spring Data compiles
    // IgnoreCase to a LOWER(...) comparison, which can't use the plain
    // idx_routes_search btree index on (origin, destination) the way an
    // exact match could - acceptable at this app's current scale (same
    // spirit as the in-memory search pagination elsewhere), but a
    // functional index on LOWER(origin), LOWER(destination) would be the
    // real fix if this table ever gets large enough for it to matter.
    //
    // ActiveTrue - a soft-deactivated route (DELETE /api/fleet/routes/{id})
    // must not keep surfacing its trips in the marketplace (and staying
    // bookable). Matches the active=true filter the locations() autocomplete
    // already applies.
    List<Route> findAllByOriginIgnoreCaseAndDestinationIgnoreCaseAndActiveTrue(String origin, String destination);

    // The operator-scoped counterpart (GET /api/fleet/trips/search,
    // OPERATOR_ADMIN): same origin/destination match as the marketplace,
    // but only the caller's own routes. Customers/guests/agents keep using
    // the cross-operator finder above; an operator only ever searches its
    // own inventory.
    List<Route> findAllByTenantIdAndOriginIgnoreCaseAndDestinationIgnoreCaseAndActiveTrue(
            UUID tenantId, String origin, String destination);

    // Backs the From/To autocomplete (GET /api/trips/locations) - a city
    // typed so far is matched as a substring against every active route's
    // origin/destination, not just an exact match like the search query
    // above. Two separate queries (rather than one UNION) because plain
    // JPQL has no UNION support; TripController merges/dedupes/sorts the
    // two lists itself. active=true only - no point suggesting a city that
    // only appears on a deactivated route, since search() would return
    // nothing for it anyway.
    @Query("SELECT DISTINCT r.origin FROM Route r WHERE r.active = true AND LOWER(r.origin) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<String> findDistinctOriginsContaining(@Param("query") String query);

    @Query("SELECT DISTINCT r.destination FROM Route r WHERE r.active = true AND LOWER(r.destination) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<String> findDistinctDestinationsContaining(@Param("query") String query);
}
