package com.bustix.scheduling;

import com.bustix.fleet.Bus;
import com.bustix.fleet.BusRepository;
import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Cross-tenant marketplace search - the read-side counterpart to the
 * "marketplace exception" repositories described in CLAUDE.md
 * (RouteRepository.findAllByOriginAndDestination,
 * TripRepository.findAllByRouteIdAndDepartureAtAfter). Both methods already
 * existed; nothing called them until now.
 *
 * Not tenant-scoped by design: a customer (or an agent booking on behalf of
 * a walk-in) searches across every operator on the platform, same as
 * BookingController's self_service/counter channels.
 */
@RestController
public class TripController {

    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final OperatorRepository operatorRepository;
    private final BusRepository busRepository;
    private final TripCreationService tripCreationService;
    private final long reportingBufferMinutes;

    public TripController(
            RouteRepository routeRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository,
            OperatorRepository operatorRepository,
            BusRepository busRepository,
            TripCreationService tripCreationService,
            @Value("${bustix.ticketing.reporting-buffer-minutes}") long reportingBufferMinutes) {
        this.routeRepository = routeRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
        this.operatorRepository = operatorRepository;
        this.busRepository = busRepository;
        this.tripCreationService = tripCreationService;
        this.reportingBufferMinutes = reportingBufferMinutes;
    }

    /**
     * page/size cap what's returned, applied in-memory after the full
     * cross-tenant result set is assembled and sorted below - not pushed
     * down into the DB query. Previously this returned every matching trip
     * unbounded, which is fine at demo scale but a real risk once an
     * operator has months of scheduled trips on a popular route. A real
     * fix would restructure the route-then-trip loop below into one
     * paginated query; that's a bigger change than this pass, so this is a
     * defensive cap, not a scalability fix - the X-Total-Count response
     * header tells a caller how much was actually cut off.
     */
    // Public - a guest browsing/booking a trip with no account needs this
    // before any login exists at all (see V8__guest_bookings.sql /
    // BookingController.createGuestBooking). Matched by a permitAll() in
    // SecurityConfig ahead of the blanket /api/** authenticated() rule;
    // this method never branches on role/JWT, so opening it to anonymous
    // callers changes nothing for the customer/agent callers already using
    // it.
    @GetMapping("/api/trips/search")
    public ResponseEntity<List<TripSearchResult>> search(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam(required = false) Instant departureAfter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Instant after = departureAfter != null ? departureAfter : Instant.now();

        // Trimmed and case-insensitive - see the IgnoreCase query's own
        // comment on RouteRepository for why: found live, a customer typing
        // any casing other than exactly how the operator entered the route
        // got zero results with no indication why.
        List<Route> routes = routeRepository.findAllByOriginIgnoreCaseAndDestinationIgnoreCase(
                origin.trim(), destination.trim());
        List<TripSearchResult> results = new ArrayList<>();

        for (Route route : routes) {
            for (Trip trip : tripRepository.findAllByRouteIdAndDepartureAtAfter(route.getId(), after)) {
                if (!"scheduled".equals(trip.getStatus())) {
                    continue;
                }
                results.add(toTripSearchResult(trip, route));
            }
        }

        results.sort(Comparator.comparing(TripSearchResult::departureAt));

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<TripSearchResult> pageOfResults = results.stream()
                .skip((long) pageNumber * pageSize)
                .limit(pageSize)
                .toList();

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(results.size()))
                .body(pageOfResults);
    }

    /**
     * Backs the From/To autocomplete on the search form - as the customer
     * (or agent) types a partial city name, this returns matching city
     * names (not trips) drawn from every active route's origin AND
     * destination, so a suggestion picked from either field always leads
     * to a real search() match. Substring, case-insensitive, capped at 10 -
     * this is a suggestion list for a dropdown, not a paginated result set
     * like search() itself. Query shorter than 2 characters returns nothing
     * rather than every city in the system.
     */
    // Public, same reasoning as search() above - the guest search box needs
    // its autocomplete before any login exists.
    @GetMapping("/api/trips/locations")
    public List<String> locations(@RequestParam String query) {
        String trimmed = query.trim();
        if (trimmed.length() < 2) {
            return List.of();
        }

        TreeSet<String> matches = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        matches.addAll(routeRepository.findDistinctOriginsContaining(trimmed));
        matches.addAll(routeRepository.findDistinctDestinationsContaining(trimmed));

        return matches.stream().limit(10).toList();
    }

    /**
     * Seat map for one trip - there was previously no way to discover a
     * seat's id through the API at all (booking requires already knowing
     * it); found while manually verifying customer self-cancel, which had
     * to query Postgres directly for an open seat id. Returns every seat
     * regardless of status (not just "open") so a client can render booked
     * seats as taken rather than simply omitting them.
     */
    // Public, same reasoning as search() above - a guest needs the seat map
    // to actually pick a seat before any login exists.
    @GetMapping("/api/trips/{tripId}/seats")
    public List<Seat> seats(@PathVariable UUID tripId) {
        if (!tripRepository.existsById(tripId)) {
            throw new NoSuchElementException("Trip not found: " + tripId);
        }
        return seatRepository.findAllByTripId(tripId);
    }

    /**
     * Single-trip lookup for the customer/agent side - added while planning
     * the frontend: the seat-selection and booking-confirmation pages need
     * to show a route/time/price summary for a trip they didn't necessarily
     * just search for (e.g. a page refresh, or "My Bookings" linking back to
     * an old trip), and previously the only single-trip GET was
     * /api/fleet/trips/{tripId}, OPERATOR_ADMIN-only and tenant-scoped.
     * Deliberately not filtered to status=scheduled or a departureAfter
     * cutoff the way search() is - a customer needs to look back at a past
     * or already-cancelled trip in their booking history too, not just
     * upcoming ones.
     */
    // Public, same reasoning as search() above - the guest booking
    // confirmation/refresh path needs to re-fetch a trip summary with no
    // login too.
    @GetMapping("/api/trips/{tripId}")
    public TripSearchResult getTripDetails(@PathVariable UUID tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));
        Route route = routeRepository.findById(trip.getRouteId())
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + trip.getRouteId()));
        return toTripSearchResult(trip, route);
    }

    /** Shared by search() and getTripDetails() above - same composed response, different entry points. */
    private TripSearchResult toTripSearchResult(Trip trip, Route route) {
        long availableSeats = seatRepository.countByTripIdAndStatus(trip.getId(), "open");
        Operator operator = operatorRepository.findById(trip.getTenantId()).orElse(null);
        String operatorName = operator != null ? operator.getName() : "Unknown";
        String operatorTin = operator != null ? operator.getTin() : null;
        String busPlateNo = busRepository.findById(trip.getBusId()).map(Bus::getPlateNo).orElse(null);

        return new TripSearchResult(
                trip.getId(),
                trip.getTenantId(),
                operatorName,
                operatorTin,
                route.getOrigin(),
                route.getDestination(),
                route.getOriginTerminal(),
                route.getDestinationTerminal(),
                trip.getDepartureAt(),
                trip.getArrivalAt(),
                trip.getDepartureAt().minus(Duration.ofMinutes(reportingBufferMinutes)),
                trip.getPrice(),
                availableSeats,
                busPlateNo);
    }

    // --- Below: staff-scoped fleet management, not the marketplace search above. ---

    // AGENT allowed here too (not on get/create/update/cancel below) - the
    // cargo waybill UI (shared by AGENT/OPERATOR_ADMIN, see
    // CargoWaybillController) needs a trip picker scoped to the caller's
    // own operator, and this tenant-scoped list is exactly that; nothing
    // else an agent could do with a bare Trip list that they can't already
    // do via GET /api/trips/search.
    @GetMapping("/api/fleet/trips")
    @PreAuthorize("hasAnyRole('OPERATOR_ADMIN', 'AGENT')")
    public List<Trip> list() {
        return tripRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/fleet/trips/{tripId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Trip get(@PathVariable UUID tripId) {
        return findOwnedTrip(tripId);
    }

    @PostMapping("/api/fleet/trips")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Trip create(@Valid @RequestBody CreateTripRequest request) {
        return tripCreationService.createTrip(request, TenantContext.require());
    }

    @PatchMapping("/api/fleet/trips/{tripId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Trip update(@PathVariable UUID tripId, @Valid @RequestBody UpdateTripRequest request) {
        Trip trip = findOwnedTrip(tripId);

        if (request.departureAt() != null) {
            trip.setDepartureAt(request.departureAt());
        }
        if (request.arrivalAt() != null) {
            trip.setArrivalAt(request.arrivalAt());
        }
        if (request.price() != null) {
            trip.setPrice(request.price());
        }
        return tripRepository.save(trip);
    }

    /**
     * Sets status to "cancelled" rather than deleting the row - a trip can
     * be referenced by existing seats/bookings, so removing it outright
     * would either violate those foreign keys or silently orphan history.
     * Deliberately does NOT touch any existing bookings on this trip (no
     * automatic refund, no notification) - that cascading behavior is a
     * separate, bigger feature (see CLAUDE.md's "trip lifecycle
     * transitions" note); this is only the minimal status flip.
     */
    @DeleteMapping("/api/fleet/trips/{tripId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Trip cancel(@PathVariable UUID tripId) {
        Trip trip = findOwnedTrip(tripId);
        trip.setStatus("cancelled");
        return tripRepository.save(trip);
    }

    private Trip findOwnedTrip(UUID tripId) {
        return tripRepository.findByIdAndTenantId(tripId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
