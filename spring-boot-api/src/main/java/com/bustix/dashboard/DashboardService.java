package com.bustix.dashboard;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.cargo.CargoWaybill;
import com.bustix.cargo.CargoWaybillRepository;
import com.bustix.fleet.BusRepository;
import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.payment.PaymentRepository;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only aggregation behind the four dashboard endpoints
 * (DashboardController). Every number is a bounded COUNT/SUM, a capped (<=8)
 * list, or a per-day GROUP BY over the selected window - no unbounded scans.
 * Tenant scoping is the caller's responsibility: the controller passes an
 * explicit tenantId (operator/agent) or customerUserId (customer), or nothing
 * at all (platform_admin, cross-tenant by nature).
 *
 * v2 (analytics): operator/platform take a {@link DashboardPeriod}; the daily
 * time series comes from native date_trunc queries (the only native SQL in
 * this codebase - JPQL has no date_trunc), gap-filled here so the frontend
 * plots a continuous axis.
 */
@Service
public class DashboardService {

    /** A waybill still physically in flight - not yet collected or cancelled. */
    private static final List<String> ACTIVE_WAYBILL_STATUSES = List.of("issued", "dispatched", "arrived");

    /** Fixed lookback for the agent card sparklines - that page has no period selector. */
    private static final int AGENT_SPARKLINE_DAYS = 14;

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final CargoWaybillRepository cargoWaybillRepository;
    private final OperatorRepository operatorRepository;
    private final PaymentRepository paymentRepository;

    public DashboardService(
            BookingRepository bookingRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository,
            RouteRepository routeRepository,
            BusRepository busRepository,
            CargoWaybillRepository cargoWaybillRepository,
            OperatorRepository operatorRepository,
            PaymentRepository paymentRepository) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
        this.routeRepository = routeRepository;
        this.busRepository = busRepository;
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.operatorRepository = operatorRepository;
        this.paymentRepository = paymentRepository;
    }

    // ---- operator_admin ----

    public OperatorDashboard operatorDashboard(UUID tenantId, DashboardPeriod period) {
        Window w = Window.of(period);

        var bookings = new TrendCount(
                bookingRepository.countByTenantIdAndCreatedAtAfter(tenantId, w.since),
                bookingRepository.countByTenantIdAndCreatedAtBetween(tenantId, w.priorSince, w.since),
                0,
                bookingRepository.countByTenantIdAndStatusAndCreatedAtAfter(tenantId, "cancelled", w.since));
        bookings = new TrendCount(bookings.current(), bookings.previous(),
                TrendCount.pct(bookings.current(), bookings.previous()), bookings.cancelledCurrent());

        var revenue = TrendMoney.of(
                bookingRepository.sumConfirmedRevenueSince(tenantId, w.since),
                bookingRepository.sumConfirmedRevenueBetween(tenantId, w.priorSince, w.since));

        var fleet = new OperatorDashboard.Fleet(
                busRepository.countByTenantIdAndActiveTrue(tenantId),
                routeRepository.countByTenantIdAndActiveTrue(tenantId),
                tripRepository.countByTenantIdAndStatusAndDepartureAtAfter(tenantId, "scheduled", w.now));

        BigDecimal cargoNow = cargoWaybillRepository.sumCargoRevenueSince(tenantId, w.since);
        BigDecimal cargoPrev = cargoWaybillRepository.sumCargoRevenueBetween(tenantId, w.priorSince, w.since);
        var cargo = new OperatorDashboard.Cargo(
                cargoWaybillRepository.countByTenantIdAndStatusIn(tenantId, ACTIVE_WAYBILL_STATUSES),
                cargoNow == null ? BigDecimal.ZERO : cargoNow,
                TrendCount.pct(nz(cargoNow).doubleValue(), nz(cargoPrev).doubleValue()));

        DailySeries series = zeroFillSeries(
                bookingRepository.dailySeries(tenantId, w.since), w.startDate, w.today);

        var breakdowns = new OperatorDashboard.Breakdowns(
                toBreakdowns(bookingRepository.channelBreakdown(tenantId, w.since)),
                toBreakdowns(bookingRepository.statusBreakdown(tenantId, w.since)),
                toBreakdowns(cargoWaybillRepository.cargoStatusBreakdown(tenantId, w.since)),
                toBreakdowns(paymentRepository.paymentMethodBreakdown(tenantId, w.since)));

        List<RouteStat> topRoutes = toRouteStats(bookingRepository.topRoutesByRevenue(tenantId, w.since));

        List<DepartureSummary> upcomingDepartures = departures(
                tripRepository.findTop8ByTenantIdAndStatusAndDepartureAtAfterOrderByDepartureAtAsc(
                        tenantId, "scheduled", w.now));
        List<DepartureSummary> occupancy = upcomingDepartures.stream()
                .sorted(Comparator.comparingDouble(DepartureSummary::rate).reversed())
                .toList();

        List<BookingSummary> recentBookings = bookingRepository
                .findTop8ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(BookingSummary::of)
                .toList();

        return new OperatorDashboard(
                period.wire(), bookings, revenue, fleet, cargo, series, breakdowns,
                topRoutes, occupancy, recentBookings, upcomingDepartures);
    }

    // ---- agent (light - fixed sparkline, no period selector) ----

    public AgentDashboard agentDashboard(UUID tenantId, UUID agentUserId) {
        Instant now = Instant.now();
        Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);
        Instant last7d = now.minus(7, ChronoUnit.DAYS);

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate sparkStart = today.minusDays(AGENT_SPARKLINE_DAYS - 1L);
        Instant sparkSince = sparkStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Long> sparkline = zeroFillCounts(
                bookingRepository.agentDailyBookingSeries(tenantId, agentUserId, sparkSince), sparkStart, today);

        var myCounter = new AgentDashboard.CounterCounts(
                bookingRepository.countByTenantIdAndAgentUserIdAndCreatedAtAfter(tenantId, agentUserId, startOfToday),
                bookingRepository.countByTenantIdAndAgentUserIdAndCreatedAtAfter(tenantId, agentUserId, last7d));

        List<DepartureSummary> next24h = departures(
                tripRepository.findByTenantIdAndStatusAndDepartureAtBetweenOrderByDepartureAtAsc(
                        tenantId, "scheduled", now, now.plus(24, ChronoUnit.HOURS)));

        List<BookingSummary> recentBookings = bookingRepository
                .findTop8ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(BookingSummary::of)
                .toList();

        return new AgentDashboard(
                myCounter,
                bookingRepository.countByTenantIdAndCreatedAtAfter(tenantId, startOfToday),
                cargoWaybillRepository.countByTenantIdAndStatus(tenantId, "requested"),
                cargoWaybillRepository.countByTenantIdAndStatusIn(tenantId, ACTIVE_WAYBILL_STATUSES),
                sparkline,
                next24h,
                recentBookings);
    }

    // ---- platform_admin (cross-tenant) ----

    public PlatformDashboard platformDashboard(DashboardPeriod period) {
        Window w = Window.of(period);

        var operators = new PlatformDashboard.Operators(
                operatorRepository.count(),
                operatorRepository.countByStatus("active"),
                operatorRepository.countByStatus("inactive"));

        var bookings = new TrendCount(
                bookingRepository.countByCreatedAtAfter(w.since),
                bookingRepository.countByCreatedAtBetween(w.priorSince, w.since),
                0,
                bookingRepository.countByStatusAndCreatedAtAfter("cancelled", w.since));
        bookings = new TrendCount(bookings.current(), bookings.previous(),
                TrendCount.pct(bookings.current(), bookings.previous()), bookings.cancelledCurrent());

        var revenue = TrendMoney.of(
                bookingRepository.sumAllConfirmedRevenueSince(w.since),
                bookingRepository.sumAllConfirmedRevenueBetween(w.priorSince, w.since));

        var cargo = new PlatformDashboard.Cargo(
                cargoWaybillRepository.countByStatusInAndTenantIdNotNull(ACTIVE_WAYBILL_STATUSES),
                cargoWaybillRepository.countByStatus("requested"));

        DailySeries series = zeroFillSeries(
                bookingRepository.dailySeriesAllTenants(w.since), w.startDate, w.today);

        var breakdowns = new PlatformDashboard.Breakdowns(
                toBreakdowns(bookingRepository.channelBreakdownAllTenants(w.since)),
                toBreakdowns(bookingRepository.statusBreakdownAllTenants(w.since)));

        List<RouteStat> topRoutes = toRouteStats(bookingRepository.topRoutesByRevenueAllTenants(w.since));

        List<Object[]> opRows = bookingRepository.topOperatorsByBookingsSince(w.since);
        List<UUID> topTenantIds = opRows.stream().limit(5).map(r -> asUuid(r[0])).toList();
        Map<UUID, Operator> topOps = index(operatorRepository.findAllById(topTenantIds), Operator::getId);
        List<PlatformDashboard.TopOperator> topOperators = opRows.stream().limit(5).map(r -> {
            UUID id = asUuid(r[0]);
            Operator op = topOps.get(id);
            return new PlatformDashboard.TopOperator(
                    id, op != null ? op.getName() : "(unknown)", ((Number) r[1]).longValue(), toBigDecimal(r[2]));
        }).toList();

        List<PlatformDashboard.OperatorSummary> recentOperators = operatorRepository
                .findTop5ByOrderByCreatedAtDesc().stream()
                .map(o -> new PlatformDashboard.OperatorSummary(o.getId(), o.getName(), o.getStatus(), o.getCreatedAt()))
                .toList();

        return new PlatformDashboard(
                period.wire(), operators, bookings, revenue,
                tripRepository.countByStatusAndDepartureAtAfter("scheduled", w.now),
                cargo, series, breakdowns, topRoutes, topOperators, recentOperators);
    }

    // ---- customer (ownership-scoped, unchanged from v1 - no analytics) ----

    public CustomerDashboard customerDashboard(UUID customerUserId) {
        Instant now = Instant.now();

        List<Booking> bookings = bookingRepository.findAllByCustomerUserId(customerUserId);
        Map<UUID, Trip> trips = index(
                tripRepository.findAllById(bookings.stream().map(Booking::getTripId).distinct().toList()),
                Trip::getId);
        Map<UUID, Route> routes = index(
                routeRepository.findAllById(trips.values().stream().map(Trip::getRouteId).distinct().toList()),
                Route::getId);

        long upcoming = 0;
        long past = 0;
        long cancelled = 0;
        List<CustomerDashboard.UpcomingTrip> upcomingTrips = new ArrayList<>();
        for (Booking b : bookings) {
            if ("cancelled".equals(b.getStatus())) {
                cancelled++;
                continue;
            }
            Trip trip = trips.get(b.getTripId());
            Instant departureAt = trip != null ? trip.getDepartureAt() : null;
            if (departureAt != null && departureAt.isAfter(now)) {
                upcoming++;
                Route route = trip != null ? routes.get(trip.getRouteId()) : null;
                upcomingTrips.add(new CustomerDashboard.UpcomingTrip(
                        b.getId(), b.getBookingRef(), routeName(route), departureAt, b.getStatus()));
            } else {
                past++;
            }
        }
        upcomingTrips.sort(Comparator.comparing(CustomerDashboard.UpcomingTrip::departureAt));
        List<CustomerDashboard.UpcomingTrip> upcomingTop = upcomingTrips.stream().limit(5).toList();

        List<CargoWaybill> activeShipments = cargoWaybillRepository.findAllOwnedByCustomer(customerUserId).stream()
                .filter(w -> !"collected".equals(w.getStatus()) && !"cancelled".equals(w.getStatus()))
                .limit(5)
                .toList();
        Map<UUID, Trip> shipmentTrips = index(
                tripRepository.findAllById(activeShipments.stream()
                        .map(CargoWaybill::getTripId).filter(Objects::nonNull).distinct().toList()),
                Trip::getId);
        Map<UUID, Route> shipmentRoutes = index(
                routeRepository.findAllById(shipmentTrips.values().stream()
                        .map(Trip::getRouteId).distinct().toList()),
                Route::getId);
        List<CustomerDashboard.ShipmentSummary> shipments = activeShipments.stream().map(w -> {
            Trip trip = w.getTripId() != null ? shipmentTrips.get(w.getTripId()) : null;
            Route route = trip != null ? shipmentRoutes.get(trip.getRouteId()) : null;
            return new CustomerDashboard.ShipmentSummary(
                    w.getId(), w.getWaybillNumber(), w.getStatus(),
                    routeName(route), trip != null ? trip.getDepartureAt() : null);
        }).toList();

        return new CustomerDashboard(
                new CustomerDashboard.Counts(upcoming, past, cancelled), upcomingTop, shipments);
    }

    // ---- helpers ----

    /** The selected window plus the equal-length window before it, all as aligned UTC-day boundaries. */
    private record Window(Instant now, LocalDate today, LocalDate startDate, Instant since, Instant priorSince) {
        static Window of(DashboardPeriod period) {
            Instant now = Instant.now();
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            LocalDate startDate = today.minusDays(period.days() - 1L);
            Instant since = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant priorSince = startDate.minusDays(period.days()).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new Window(now, today, startDate, since, priorSince);
        }
    }

    private List<DepartureSummary> departures(List<Trip> trips) {
        Map<UUID, Route> routes = index(
                routeRepository.findAllById(trips.stream().map(Trip::getRouteId).distinct().toList()),
                Route::getId);
        return trips.stream()
                .map(t -> {
                    long booked = seatRepository.countByTripIdAndStatus(t.getId(), "booked");
                    long capacity = seatRepository.countByTripId(t.getId());
                    double rate = capacity == 0 ? 0.0 : (double) booked / capacity;
                    return new DepartureSummary(
                            t.getId(), routeName(routes.get(t.getRouteId())), t.getDepartureAt(),
                            booked, capacity, rate);
                })
                .toList();
    }

    /** rows: [day 'YYYY-MM-DD', bookings, revenue, cancellations] - fill every day in [start, end] with zeros where absent. */
    private DailySeries zeroFillSeries(List<Object[]> rows, LocalDate start, LocalDate end) {
        Map<String, Object[]> byDay = new HashMap<>();
        for (Object[] r : rows) {
            byDay.put((String) r[0], r);
        }
        List<String> days = new ArrayList<>();
        List<Long> bookings = new ArrayList<>();
        List<BigDecimal> revenue = new ArrayList<>();
        List<Long> cancellations = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String key = d.toString(); // ISO-8601 YYYY-MM-DD, matches to_char(...,'YYYY-MM-DD')
            Object[] r = byDay.get(key);
            days.add(key);
            bookings.add(r == null ? 0L : ((Number) r[1]).longValue());
            revenue.add(r == null ? BigDecimal.ZERO : toBigDecimal(r[2]));
            cancellations.add(r == null ? 0L : ((Number) r[3]).longValue());
        }
        return new DailySeries(days, bookings, revenue, cancellations);
    }

    /** rows: [day 'YYYY-MM-DD', count] - a single zero-filled count series (agent sparkline). */
    private List<Long> zeroFillCounts(List<Object[]> rows, LocalDate start, LocalDate end) {
        Map<String, Long> byDay = new HashMap<>();
        for (Object[] r : rows) {
            byDay.put((String) r[0], ((Number) r[1]).longValue());
        }
        List<Long> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            out.add(byDay.getOrDefault(d.toString(), 0L));
        }
        return out;
    }

    /** rows: [key, count] or [key, count, amount] - the amount column is optional (cargo status has none). */
    private static List<Breakdown> toBreakdowns(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new Breakdown(
                        r[0] == null ? "unknown" : r[0].toString(),
                        ((Number) r[1]).longValue(),
                        r.length > 2 ? toBigDecimal(r[2]) : BigDecimal.ZERO))
                .toList();
    }

    /** rows: [routeId, origin, destination, bookings, revenue] from the native top-routes join. */
    private static List<RouteStat> toRouteStats(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new RouteStat(
                        asUuid(r[0]),
                        str(r[1]) + " → " + str(r[2]),
                        ((Number) r[3]).longValue(),
                        toBigDecimal(r[4])))
                .toList();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static UUID asUuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String routeName(Route route) {
        return route == null ? null : route.getOrigin() + " → " + route.getDestination();
    }

    private static <T> Map<UUID, T> index(Iterable<T> rows, Function<T, UUID> key) {
        List<T> list = new ArrayList<>();
        rows.forEach(list::add);
        return list.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }

    /** A SUM/count projection comes back as a Number whose concrete type Hibernate/the driver picks - normalize it. */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }
}
