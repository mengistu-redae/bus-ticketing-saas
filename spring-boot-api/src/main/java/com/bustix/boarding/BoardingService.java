package com.bustix.boarding;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.booking.BookingSeat;
import com.bustix.booking.BookingSeatRepository;
import com.bustix.refund.BookingAlreadyCancelledException;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The Boarding Gate State Machine's "Validation Engine" and "Gate Lockout"
 * rules (my-notes/ethiopian_bus_system_specs.md section 4.1). "Gate
 * Lockout" is enforced here by comparing against the trip's own
 * departure_at at call time - not by trusting Trip.status having already
 * been flipped to "boarding_closed" by TripLifecycleScheduler, since that
 * scheduled job runs on a polling interval and must never be the actual
 * security boundary for a real-time gate decision. The scheduled flip
 * exists for dashboard/search visibility (a boarding-closed trip
 * disappearing from marketplace search), not enforcement.
 */
@Service
public class BoardingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;

    public BoardingService(
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public CheckInResult checkIn(UUID bookingId, UUID seatId, UUID tenantId, String presentedIdNumber) {
        Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        if ("cancelled".equals(booking.getStatus())) {
            throw new BookingAlreadyCancelledException("Booking already cancelled: " + booking.getId());
        }

        Trip trip = tripRepository.findById(booking.getTripId())
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + booking.getTripId()));

        // "Gate Lockout: ... exactly at [departure]. No exceptions or
        // post-departure changes allowed" - checked live against the
        // trip's own departureAt, see the class javadoc for why this isn't
        // a read of Trip.status instead.
        if (!Instant.now().isBefore(trip.getDepartureAt())) {
            throw new BoardingClosedException(
                    "Boarding closed for trip " + trip.getId() + " - departed at " + trip.getDepartureAt());
        }

        BookingSeat.Id id = new BookingSeat.Id();
        id.setBookingId(bookingId);
        id.setSeatId(seatId);
        BookingSeat bookingSeat = bookingSeatRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Seat not booked on this booking: " + seatId));

        // Idempotent: re-checking in an already-boarded passenger (e.g. a
        // retried gate scan) just returns the existing state rather than
        // erroring.
        if (!"boarded".equals(bookingSeat.getBoardingStatus())) {
            String onFile = bookingSeat.getPassengerIdNumber();
            if (onFile == null || onFile.isBlank() || !onFile.equals(presentedIdNumber)) {
                throw new IdentityMismatchException(
                        "Presented ID does not match the ID on file for seat " + seatId);
            }
            bookingSeat.setBoardingStatus("boarded");
            bookingSeat.setBoardedAt(Instant.now());
            bookingSeat = bookingSeatRepository.save(bookingSeat);
        }

        Seat seat = seatRepository.findById(seatId).orElse(null);
        return new CheckInResult(
                seatId,
                seat != null ? seat.getSeatNo() : null,
                bookingSeat.getPassengerName(),
                bookingSeat.getBoardingStatus(),
                bookingSeat.getBoardedAt());
    }
}
