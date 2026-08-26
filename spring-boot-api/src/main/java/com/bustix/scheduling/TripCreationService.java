package com.bustix.scheduling;

import com.bustix.fleet.Bus;
import com.bustix.fleet.BusRepository;
import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Creating a trip also generates its seats up front from the bus's
 * capacity/seat_layout - see SeatLayoutGenerator. One @Transactional method
 * so a trip is never left without seats (or seats without a trip) if
 * something fails partway - same shape as BookingWriter/CancellationService
 * (see the note on those about @Transactional and self-invocation: this is
 * a separate bean called from TripController, not a same-class call, so the
 * proxy applies correctly).
 */
@Service
public class TripCreationService {

    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;

    public TripCreationService(
            RouteRepository routeRepository,
            BusRepository busRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository) {
        this.routeRepository = routeRepository;
        this.busRepository = busRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public Trip createTrip(CreateTripRequest request, UUID tenantId) {
        Route route = routeRepository.findByIdAndTenantId(request.routeId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + request.routeId()));
        Bus bus = busRepository.findByIdAndTenantId(request.busId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Bus not found: " + request.busId()));

        Trip trip = new Trip();
        trip.setTenantId(tenantId);
        trip.setRouteId(route.getId());
        trip.setBusId(bus.getId());
        trip.setDepartureAt(request.departureAt());
        trip.setArrivalAt(request.arrivalAt());
        trip.setPrice(request.price());
        trip = tripRepository.save(trip);

        UUID tripId = trip.getId();
        List<Seat> seats = SeatLayoutGenerator.generate(bus.getCapacity(), bus.getSeatLayout()).stream()
                .map(seatNo -> {
                    Seat seat = new Seat();
                    seat.setTripId(tripId);
                    seat.setSeatNo(seatNo);
                    return seat;
                })
                .collect(Collectors.toList());
        seatRepository.saveAll(seats);

        return trip;
    }
}
