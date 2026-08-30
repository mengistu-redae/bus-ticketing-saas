package com.bustix.booking;

import com.bustix.notification.Notification;
import com.bustix.notification.NotificationRepository;
import com.bustix.operator.EffectiveOperatorSettings;
import com.bustix.operator.OperatorSettingsService;
import com.bustix.refund.BookingAlreadyCancelledException;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import com.bustix.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Rescheduling (my-notes/ethiopian_bus_system_specs.md section 5.3): moves
 * a booking to a different trip/seat rather than cancel-and-rebook, so the
 * passenger keeps the same booking id/ticket number/PNR. Mirrors
 * CancellationService's shape (one @Transactional method per access path,
 * both delegating to a shared private apply* method) for the same
 * self-invocation/@Transactional-proxy reason documented on BookingWriter.
 *
 * v1 only supports single-seat bookings. A multi-seat booking's seats
 * could each need moving to a different new seat (possibly on a different
 * new trip, possibly not all rescheduled at once) - genuinely ambiguous
 * without an explicit per-seat mapping the BRD doesn't specify, and a
 * bigger feature than this pass covers. Confirmed with the user before
 * building rather than assumed.
 */
@Service
public class BookingRescheduleService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingInfantRepository bookingInfantRepository;
    private final BookingRescheduleRepository bookingRescheduleRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;
    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;
    private final OperatorSettingsService operatorSettingsService;

    public BookingRescheduleService(
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingInfantRepository bookingInfantRepository,
            BookingRescheduleRepository bookingRescheduleRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository,
            SeatLockService seatLockService,
            NotificationRepository notificationRepository,
            AppUserRepository appUserRepository,
            OperatorSettingsService operatorSettingsService) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingInfantRepository = bookingInfantRepository;
        this.bookingRescheduleRepository = bookingRescheduleRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
        this.seatLockService = seatLockService;
        this.notificationRepository = notificationRepository;
        this.appUserRepository = appUserRepository;
        this.operatorSettingsService = operatorSettingsService;
    }

    /** Staff path - tenant-scoped, same shape as CancellationService.cancel. */
    @Transactional
    public Booking reschedule(UUID bookingId, UUID tenantId, UUID newTripId, UUID newSeatId, UUID actingUserId) {
        Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
        return applyReschedule(booking, newTripId, newSeatId, actingUserId);
    }

    /** Customer self-service path - ownership-scoped, same shape as CancellationService.cancelAsCustomer. */
    @Transactional
    public Booking rescheduleAsCustomer(UUID bookingId, UUID customerUserId, UUID newTripId, UUID newSeatId) {
        Booking booking = bookingRepository.findByIdAndCustomerUserId(bookingId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));
        return applyReschedule(booking, newTripId, newSeatId, customerUserId);
    }

    private Booking applyReschedule(Booking booking, UUID newTripId, UUID newSeatId, UUID actingUserId) {
        if ("cancelled".equals(booking.getStatus())) {
            throw new BookingAlreadyCancelledException("Booking already cancelled: " + booking.getId());
        }

        // Effective settings for this booking's operator - the reschedule
        // time gate, mutation fees and VAT rate are all operator-overridable
        // (falling back to the application.yml defaults), and the
        // "booking_rescheduled" notice below is gated on the operator's
        // reschedule-notifications toggle.
        EffectiveOperatorSettings settings = operatorSettingsService.resolve(booking.getTenantId());
        long minNoticeHours = settings.rescheduleMinNoticeHours();

        List<BookingSeat> existingSeats = bookingSeatRepository.findAllByIdBookingId(booking.getId());
        if (existingSeats.size() != 1) {
            throw new MultiSeatRescheduleNotSupportedException(
                    "Rescheduling is only supported for single-seat bookings; this booking has "
                            + existingSeats.size() + " seats - cancel and rebook instead.");
        }
        BookingSeat oldBookingSeat = existingSeats.get(0);
        UUID oldSeatId = oldBookingSeat.getId().getSeatId();

        Trip oldTrip = tripRepository.findById(booking.getTripId())
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + booking.getTripId()));

        // "Time Gate: ... evaluate Delta T >= 12 hours. If invalid, block
        // the request and route to the refund engine" - see the BRD
        // section cited on TooLateToRescheduleException.
        long hoursUntilDeparture = Duration.between(Instant.now(), oldTrip.getDepartureAt()).toHours();
        if (hoursUntilDeparture < minNoticeHours) {
            throw new TooLateToRescheduleException(
                    "Rescheduling requires at least " + minNoticeHours
                            + " hours' notice before departure - cancel this booking instead for a refund"
                            + " per the applicable policy.");
        }

        Trip newTrip = tripRepository.findById(newTripId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + newTripId));
        if (!newTrip.getTenantId().equals(booking.getTenantId())) {
            // A booking belongs to one operator's inventory - moving it to
            // a different operator's trip isn't "rescheduling the same
            // ticket," it's a new booking. Same tenant-ownership principle
            // BookingService enforces at creation time via TenantMismatchException,
            // reused here for the equivalent guard.
            throw new TenantMismatchException("New trip must belong to the same operator as the existing booking");
        }

        String lockToken = UUID.randomUUID().toString();
        if (!seatLockService.tryAcquire(newSeatId.toString(), lockToken)) {
            throw new SeatConflictException("Seat already held by another request: " + newSeatId);
        }
        try {
            Seat newSeat = seatRepository.findByIdAndTripId(newSeatId, newTripId)
                    .orElseThrow(() -> new NoSuchElementException("Seat not found on this trip: " + newSeatId));
            if (!"open".equals(newSeat.getStatus())) {
                throw new SeatConflictException("Seat no longer available: " + newSeatId);
            }

            newSeat.setStatus("booked");
            seatRepository.save(newSeat);

            BigDecimal fee = "counter".equals(booking.getChannel())
                    ? settings.rescheduleFeeCounter()
                    : settings.rescheduleFeeSelfService();
            BigDecimal newSubtotal = newTrip.getPrice();
            BigDecimal newTax = newSubtotal.multiply(settings.vatRate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newTotal = newSubtotal.add(newTax).add(fee);

            // Move the booking_seats row: BookingSeat.Id embeds seatId, so
            // this can't be an in-place update - insert the new row first
            // (carrying every passenger field over unchanged, per the
            // BRD's "Immutability Principle": only trip/seat move, never
            // identity), then repoint any infants at it, and only then
            // delete the old row. Order matters - booking_infants has a
            // composite FK to booking_seats(booking_id, seat_id), so an
            // infant row must always reference a booking_seats row that
            // currently exists; deleting the old row before repointing its
            // infants would violate that FK.
            BookingSeat newBookingSeat = new BookingSeat();
            BookingSeat.Id newId = new BookingSeat.Id();
            newId.setBookingId(booking.getId());
            newId.setSeatId(newSeatId);
            newBookingSeat.setId(newId);
            newBookingSeat.setPrice(newTrip.getPrice());
            newBookingSeat.setPassengerName(oldBookingSeat.getPassengerName());
            newBookingSeat.setPassengerPhone(oldBookingSeat.getPassengerPhone());
            newBookingSeat.setPassengerIdNumber(oldBookingSeat.getPassengerIdNumber());
            newBookingSeat.setPassengerIdType(oldBookingSeat.getPassengerIdType());
            newBookingSeat.setPassengerAge(oldBookingSeat.getPassengerAge());
            // Boarding status/boardedAt deliberately reset, not carried
            // over - the passenger hasn't boarded this new trip.
            bookingSeatRepository.save(newBookingSeat);

            for (BookingInfant infant : bookingInfantRepository.findAllByBookingId(booking.getId())) {
                if (infant.getSeatId().equals(oldSeatId)) {
                    infant.setSeatId(newSeatId);
                    bookingInfantRepository.save(infant);
                }
            }

            bookingSeatRepository.delete(oldBookingSeat);

            // Free the old seat only after the row referencing it is gone.
            seatRepository.findById(oldSeatId).ifPresent(s -> {
                s.setStatus("open");
                seatRepository.save(s);
            });

            booking.setTripId(newTripId);
            booking.setSubtotalAmount(newSubtotal);
            booking.setTaxAmount(newTax);
            booking.setTotalAmount(newTotal);
            booking.setRescheduleFee(fee);
            // Reassigned once above, so not effectively final - captured
            // into this new final variable for the lambda below.
            final Booking savedBooking = bookingRepository.save(booking);

            BookingReschedule reschedule = new BookingReschedule();
            reschedule.setBookingId(savedBooking.getId());
            reschedule.setOldTripId(oldTrip.getId());
            reschedule.setNewTripId(newTripId);
            reschedule.setOldSeatId(oldSeatId);
            reschedule.setNewSeatId(newSeatId);
            reschedule.setFee(fee);
            reschedule.setRescheduledBy(actingUserId);
            bookingRescheduleRepository.save(reschedule);

            // Outbox write, same pattern as CancellationService: recipient
            // is the customer on the booking, looked up via app_user
            // rather than trusting any staff/customer field on the request
            // itself - uniform regardless of which access path called this.
            // customerUserId is null for a guest booking - see
            // CancellationService's identical guard for why this skips the
            // lookup entirely rather than crashing (appUserRepository.
            // findById(null) throws, and a guest has no email on file
            // anyway). Gated on the operator's reschedule-notifications
            // toggle (default on) - the same switch that governs the
            // trip-time-change cascade in TripUpdateService.
            if (settings.rescheduleNotificationsEnabled() && savedBooking.getCustomerUserId() != null) {
                appUserRepository.findById(savedBooking.getCustomerUserId()).ifPresent(customer -> {
                    Notification notification = new Notification();
                    notification.setBookingId(savedBooking.getId());
                    notification.setChannel("email");
                    notification.setRecipient(customer.getEmail());
                    notification.setTemplate("booking_rescheduled");
                    notificationRepository.save(notification);
                });
            }

            return savedBooking;
        } finally {
            seatLockService.release(newSeatId.toString(), lockToken);
        }
    }
}
