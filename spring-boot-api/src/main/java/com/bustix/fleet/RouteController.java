package com.bustix.fleet;

import com.bustix.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Operator staff managing their own operator's routes - tenant-scoped
 * throughout. Not to be confused with TripController's cross-tenant
 * marketplace search, which reads this same table without a tenant filter.
 */
@RestController
@RequestMapping("/api/fleet/routes")
public class RouteController {

    private final RouteRepository routeRepository;

    public RouteController(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    // AGENT allowed here too (not on get/create/update/delete below) - same
    // reasoning as TripController.list(): the cargo waybill UI resolves a
    // trip's route name client-side from this tenant-scoped list, same
    // pattern the operator_admin fleet pages already use.
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR_ADMIN', 'AGENT')")
    public List<Route> list() {
        return routeRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/{routeId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Route get(@PathVariable UUID routeId) {
        return routeRepository.findByIdAndTenantId(routeId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + routeId));
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Route create(@Valid @RequestBody CreateRouteRequest request) {
        Route route = new Route();
        route.setTenantId(TenantContext.require());
        route.setOrigin(request.origin());
        route.setDestination(request.destination());
        route.setDistanceKm(request.distanceKm());
        route.setOriginTerminal(request.originTerminal());
        route.setDestinationTerminal(request.destinationTerminal());
        return routeRepository.save(route);
    }

    /** There was previously no correction path for a typo'd origin/destination/distance. */
    @PatchMapping("/{routeId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Route update(@PathVariable UUID routeId, @Valid @RequestBody UpdateRouteRequest request) {
        Route route = findOwnedRoute(routeId);

        if (request.origin() != null && !request.origin().isBlank()) {
            route.setOrigin(request.origin());
        }
        if (request.destination() != null && !request.destination().isBlank()) {
            route.setDestination(request.destination());
        }
        if (request.distanceKm() != null) {
            route.setDistanceKm(request.distanceKm());
        }
        if (request.active() != null) {
            route.setActive(request.active());
        }
        if (request.originTerminal() != null) {
            route.setOriginTerminal(request.originTerminal());
        }
        if (request.destinationTerminal() != null) {
            route.setDestinationTerminal(request.destinationTerminal());
        }
        return routeRepository.save(route);
    }

    /**
     * Soft-deactivate, not a row delete - a route can be referenced by
     * existing trips, and the cross-tenant marketplace search would need to
     * start excluding inactive routes too (not done here - out of scope for
     * this pass, same "no cascading behavior change" boundary as trip
     * deactivation below). Returns the deactivated entity, matching this
     * API's convention of returning the mutated resource from every write.
     */
    @DeleteMapping("/{routeId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Route deactivate(@PathVariable UUID routeId) {
        Route route = findOwnedRoute(routeId);
        route.setActive(false);
        return routeRepository.save(route);
    }

    private Route findOwnedRoute(UUID routeId) {
        return routeRepository.findByIdAndTenantId(routeId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + routeId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
