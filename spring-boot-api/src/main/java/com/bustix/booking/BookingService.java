package com.bustix.booking;

import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.EffectiveOperatorSettings;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorBrandingView;
import com.bustix.operator.OperatorRepository;
import com.bustix.operator.OperatorSettingsService;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Implements the flow from the "seat booking concurrency flow" diagram:
 * select seat -> acquire Redis lock -> lock acquired (write booking) or
 * already locked (conflict).
 *
 * The actual DB write lives in BookingWriter, a separate bean, so that its
 * @Transactional annotation goes through Spring's proxy correctly - calling
 * a @Transactional method on `this` from inside the same class silently
 * skips the transaction, which is an easy mistake to make here.
 */
@Service
public class BookingService {

    private final SeatLockService seatLockService;
    private final TripRepository tripRepository;
    private final RouteRepository routeRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingWriter bookingWriter;
    private final OperatorRepository operatorRepository;
    private final OperatorSettingsService operatorSettingsService;

    public BookingService(
            SeatLockService seatLockService,
            TripRepository tripRepository,
            RouteRepository routeRepository,
            SeatRepository seatRepository,
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingWriter bookingWriter,
            OperatorRepository operatorRepository,
            OperatorSettingsService operatorSettingsService) {
        this.seatLockService = seatLockService;
        this.tripRepository = tripRepository;
        this.routeRepository = routeRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingWriter = bookingWriter;
        this.operatorRepository = operatorRepository;
        this.operatorSettingsService = operatorSettingsService;
    }

    /**
     * @param channel          "self_service", "counter", "guest", or "partner" (a third-party /v1 integration)
     * @param customerUserId   set for self_service/counter, null for guest/partner
     * @param agentUserId      set only for channel = "counter"
     * @param agentTenantId    the operator the caller acts for - required and tenant-checked for "counter"/"partner"
     * @param recipientEmail   where to send the confirmation - v1 is email-only; may be null (guest/partner with no email given)
     * @param guestContactPhone the no-account booking's contact phone - set for "guest" and "partner", see Booking.guestContactPhone
     */
    public Booking createBooking(
            CreateBookingRequest request,
            String channel,
            UUID customerUserId,
            UUID agentUserId,
            UUID agentTenantId,
            String recipientEmail,
            String guestContactPhone) {

        Trip trip = tripRepository.findById(request.tripId())
            .orElseThrow(() -> new NoSuchElementException("Trip not found: " + request.tripId()));

        // "counter" (an agent at the desk) and "partner" (a third-party /v1
        // integration) both act for exactly one operator and may only book
        // that operator's trips - same tenant check for both.
        if (("counter".equals(channel) || "partner".equals(channel))
                && !trip.getTenantId().equals(agentTenantId)) {
            throw new TenantMismatchException("Caller's operator does not match this trip's operator");
        }

        // Booking-time-only enforcement of operator deactivation (see
        // PlatformController.deactivate) - deliberately checked here and
        // nowhere else: marketplace search and staff login are untouched,
        // only the booking write itself is blocked. Checked before the
        // idempotency lookup/seat lock so a doomed booking never touches
        // Redis.
        Operator operator = operatorRepository.findById(trip.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + trip.getTenantId()));
        if (!"active".equals(operator.getStatus())) {
            throw new OperatorInactiveException("This operator is not currently accepting bookings");
        }

        // Idempotency check happens before any locking - a retried request
        // with the same key should just return the original booking, not
        // fight itself for the same seats.
        var existing = bookingRepository.findByTenantIdAndIdempotencyKey(trip.getTenantId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        String lockToken = UUID.randomUUID().toString();
        List<UUID> acquiredSeatIds = new ArrayList<>();
        try {
            for (CreateBookingRequest.PassengerSeat passenger : request.passengers()) {
                UUID seatId = passenger.seatId();
                boolean acquired = seatLockService.tryAcquire(seatId.toString(), lockToken);
                if (!acquired) {
                    throw new SeatConflictException("Seat already held by another request: " + seatId);
                }
                acquiredSeatIds.add(seatId);
            }

            // The lock only proves we currently hold the seat in Redis - the
            // DB write still re-checks status='open' so a seat that was
            // booked through some other path (e.g. a manual DB fix) can't
            // be double-sold either. See BookingWriter.
            return bookingWriter.write(trip, request, channel, customerUserId, agentUserId, recipientEmail, guestContactPhone);

        } finally {
            for (UUID seatId : acquiredSeatIds) {
                seatLockService.release(seatId.toString(), lockToken);
            }
        }
    }

    /**
     * Public track-by-ref lookup for a guest booking (or, incidentally, any
     * booking whose phone happens to match) - see
     * BookingController.trackGuestBooking. Mirrors
     * CargoWaybillService.track exactly: a phone match against either the
     * booking's own guestContactPhone or any of its passengers' phones
     * stands in for an ownership check, since the caller has no session/
     * tenant/customerUserId at all. A mismatch (or unknown ref) 404s
     * identically - "exists but not yours reads as doesn't exist," same
     * convention used everywhere else in this app.
     */
    public BookingTrackingView trackByRefAndPhone(String bookingRef, String phone) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingRef));

        List<BookingSeat> bookingSeats = bookingSeatRepository.findAllByIdBookingId(booking.getId());

        boolean phoneMatches = phone.equals(booking.getGuestContactPhone())
                || bookingSeats.stream().anyMatch(seat -> phone.equals(seat.getPassengerPhone()));
        if (!phoneMatches) {
            throw new NoSuchElementException("Booking not found: " + bookingRef);
        }

        Trip trip = tripRepository.findById(booking.getTripId())
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + booking.getTripId()));
        Route route = routeRepository.findById(trip.getRouteId())
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + trip.getRouteId()));

        List<BookingTrackingView.SeatView> seatViews = bookingSeats.stream()
                .map(bookingSeat -> {
                    Seat seat = seatRepository.findById(bookingSeat.getId().getSeatId()).orElse(null);
                    return new BookingTrackingView.SeatView(
                            seat != null ? seat.getSeatNo() : null,
                            bookingSeat.getPassengerName());
                })
                .toList();

        EffectiveOperatorSettings settings = operatorSettingsService.resolve(booking.getTenantId());
        String operatorName = operatorRepository.findById(booking.getTenantId())
                .map(Operator::getName).orElse(null);

        return new BookingTrackingView(
                booking.getBookingRef(),
                booking.getTicketNumber(),
                booking.getStatus(),
                booking.getChannel(),
                trip.getId(),
                route.getOrigin(),
                route.getDestination(),
                trip.getDepartureAt(),
                booking.getSubtotalAmount(),
                booking.getTaxAmount(),
                booking.getTotalAmount(),
                seatViews,
                settings.supportPhone(),
                settings.supportEmail(),
                OperatorBrandingView.from(settings, operatorName));
    }
}
