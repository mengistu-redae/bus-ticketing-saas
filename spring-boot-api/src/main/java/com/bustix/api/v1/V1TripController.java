package com.bustix.api.v1;

import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.EffectiveOperatorSettings;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.operator.OperatorSettingsService;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import com.bustix.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Partner-facing trip read surface. Every response is a {@code /v1} DTO, not
 * an internal entity or projection - the wire format is decoupled from
 * persistence and versioned. Tenant-scoped throughout via
 * {@link TenantContext}: a partner only ever sees its own operator's trips
 * (the tenant is resolved from the token's {@code azp} by
 * {@code TenantContextFilter}).
 *
 * Gated on the {@code trips:read} OAuth scope, so a human staff token from
 * the BFF - which never requests business scopes - cannot reach {@code /v1}.
 */
@RestController
@RequestMapping("/v1/trips")
@PreAuthorize("hasAuthority('SCOPE_trips:read')")
@Tag(name = "Trips", description = "Search and read the calling partner's own operator's trips.")
public class V1TripController {

    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final OperatorRepository operatorRepository;
    private final OperatorSettingsService operatorSettingsService;

    public V1TripController(
            RouteRepository routeRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository,
            OperatorRepository operatorRepository,
            OperatorSettingsService operatorSettingsService) {
        this.routeRepository = routeRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
        this.operatorRepository = operatorRepository;
        this.operatorSettingsService = operatorSettingsService;
    }

    @GetMapping
    @Operation(summary = "Search scheduled trips on a route",
            description = "Origin/destination are matched case-insensitively against the operator's active routes. "
                    + "Returns only scheduled trips departing after departureAfter (default: now).")
    public PageEnvelope<TripView> search(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam(required = false) Instant departureAfter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID tenantId = TenantContext.require();
        Instant after = departureAfter != null ? departureAfter : Instant.now();

        List<Route> routes = routeRepository
                .findAllByTenantIdAndOriginIgnoreCaseAndDestinationIgnoreCaseAndActiveTrue(
                        tenantId, origin.trim(), destination.trim());

        EffectiveOperatorSettings settings = operatorSettingsService.resolve(tenantId);
        String operatorName = operatorName(tenantId);

        List<TripView> all = new ArrayList<>();
        for (Route route : routes) {
            for (Trip trip : tripRepository.findAllByRouteIdAndDepartureAtAfter(route.getId(), after)) {
                if ("scheduled".equals(trip.getStatus())) {
                    all.add(toView(trip, route, tenantId, operatorName, settings));
                }
            }
        }
        all.sort(Comparator.comparing(TripView::departureAt));
        return PageEnvelope.of(all, page, size);
    }

    @GetMapping("/{tripId}")
    @Operation(summary = "Get one trip")
    public TripView get(@PathVariable UUID tripId) {
        UUID tenantId = TenantContext.require();
        Trip trip = ownedTrip(tripId, tenantId);
        Route route = routeRepository.findById(trip.getRouteId())
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + trip.getRouteId()));
        return toView(trip, route, tenantId, operatorName(tenantId), operatorSettingsService.resolve(tenantId));
    }

    @GetMapping("/{tripId}/seats")
    @Operation(summary = "Get a trip's seat map",
            description = "Every seat regardless of status, so booked seats render as taken.")
    public List<SeatView> seats(@PathVariable UUID tripId) {
        UUID tenantId = TenantContext.require();
        ownedTrip(tripId, tenantId);
        return seatRepository.findAllByTripId(tripId).stream()
                .map(this::toSeatView)
                .toList();
    }

    private Trip ownedTrip(UUID tripId, UUID tenantId) {
        return tripRepository.findByIdAndTenantId(tripId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));
    }

    private String operatorName(UUID tenantId) {
        return operatorRepository.findById(tenantId).map(Operator::getName).orElse("Unknown");
    }

    private TripView toView(Trip trip, Route route, UUID operatorId, String operatorName,
                            EffectiveOperatorSettings settings) {
        long availableSeats = seatRepository.countByTripIdAndStatus(trip.getId(), "open");
        return new TripView(
                trip.getId(),
                operatorId,
                operatorName,
                route.getOrigin(),
                route.getDestination(),
                route.getOriginTerminal(),
                route.getDestinationTerminal(),
                trip.getDepartureAt(),
                trip.getArrivalAt(),
                trip.getPrice(),
                settings.vatRate(),
                availableSeats);
    }

    private SeatView toSeatView(Seat seat) {
        return new SeatView(seat.getId(), seat.getSeatNo(), seat.getSeatClass(), seat.getStatus());
    }
}
