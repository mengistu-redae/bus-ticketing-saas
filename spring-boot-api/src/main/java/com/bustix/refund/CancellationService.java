package com.bustix.refund;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.booking.BookingSeat;
import com.bustix.booking.BookingSeatRepository;
import com.bustix.notification.Notification;
import com.bustix.notification.NotificationRepository;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import com.bustix.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Mirrors BookingWriter's shape: one @Transactional method that flips the
 * booking, frees its seats, records the refund and writes the outbox
 * notification atomically. Kept as its own bean (not a method on
 * BookingService) for the same reason BookingWriter is split out - see its
 * javadoc on self-invocation and @Transactional proxies.
 */
@Service
public class CancellationService {

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final SeatRepository seatRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final CancellationRepository cancellationRepository;
    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;
    private final RefundCalculator refundCalculator;

    public CancellationService(
            BookingRepository bookingRepository,
            TripRepository tripRepository,
            SeatRepository seatRepository,
            BookingSeatRepository bookingSeatRepository,
            CancellationRepository cancellationRepository,
            NotificationRepository notificationRepository,
            AppUserRepository appUserRepository,
            RefundCalculator refundCalculator) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.seatRepository = seatRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.cancellationRepository = cancellationRepository;
        this.notificationRepository = notificationRepository;
        this.appUserRepository = appUserRepository;
        this.refundCalculator = refundCalculator;
    }

    @Transactional
    public Cancellation cancel(UUID bookingId, UUID tenantId, UUID cancelledByUserId, String reason) {
        // Tenant-scoped lookup: an agent/operator_admin can only cancel
        // bookings belonging to their own operator - same enforcement shape
        // as BookingService's counter-channel tenant check.
        Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        return applyCancellation(booking, cancelledByUserId, reason);
    }

    /**
     * Customer self-service cancellation - the endpoint CLAUDE.md's Known
     * gaps used to call out as unbuilt. Ownership-scoped by customerUserId
     * instead of tenant-scoped: customer tokens carry no tenant (see
     * TenantContext's javadoc), so there's no TenantContext to check
     * against here the way {@link #cancel} does - the booking's own
     * tenantId (read off the row itself in applyCancellation, not supplied
     * by the caller) drives the refund policy lookup instead.
     */
    @Transactional
    public Cancellation cancelAsCustomer(UUID bookingId, UUID customerUserId, String reason) {
        Booking booking = bookingRepository.findByIdAndCustomerUserId(bookingId, customerUserId)
            .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        return applyCancellation(booking, customerUserId, reason);
    }

    /**
     * Shared by both {@link #cancel} and {@link #cancelAsCustomer} once
     * each has resolved (and ownership-checked) its own Booking - safe to
     * call from either without its own @Transactional, since it always runs
     * inside whichever public, proxied entry point called it. See
     * BookingWriter's javadoc for why that distinction matters here.
     */
    private Cancellation applyCancellation(Booking booking, UUID cancelledByUserId, String reason) {
        if ("cancelled".equals(booking.getStatus())) {
            throw new BookingAlreadyCancelledException("Booking already cancelled: " + booking.getId());
        }

        Trip trip = tripRepository.findById(booking.getTripId())
            .orElseThrow(() -> new NoSuchElementException("Trip not found: " + booking.getTripId()));

        BigDecimal refundAmount = refundCalculator.calculate(
            booking.getTenantId(), trip.getRouteId(), booking.getTotalAmount(), trip.getDepartureAt());

        booking.setStatus("cancelled");
        bookingRepository.save(booking);

        // Free the seats back up on the same seats table the booking flow
        // checks against - a cancelled seat is immediately bookable again.
        // No Redis lock to release here (unlike SeatLockService): that lock
        // is only ever held for the few moments a booking is being written,
        // it's long gone by the time anyone cancels a confirmed booking.
        for (BookingSeat bookingSeat : bookingSeatRepository.findAllByIdBookingId(booking.getId())) {
            seatRepository.findById(bookingSeat.getId().getSeatId())
                .ifPresent(this::markOpen);
        }

        Cancellation cancellation = new Cancellation();
        cancellation.setBookingId(booking.getId());
        cancellation.setCancelledBy(cancelledByUserId);
        cancellation.setReason(reason);
        cancellation.setRefundAmount(refundAmount);
        cancellation = cancellationRepository.save(cancellation);

        // Outbox write, same pattern as BookingWriter's booking_confirmed
        // notification - recipient is the customer on the booking, not
        // whichever agent/operator_admin/customer actually clicked "cancel"
        // (the latter two are the same person for cancelAsCustomer, but
        // keep the lookup uniform rather than special-casing that).
        // customerUserId is null for a guest (channel = "guest") booking -
        // appUserRepository.findById(null) throws rather than returning
        // empty, and a guest's contactEmail was never persisted anyway (see
        // BookingWriter), so there's no email to notify - skip the lookup
        // entirely rather than crashing the whole cancellation.
        if (booking.getCustomerUserId() != null) {
            appUserRepository.findById(booking.getCustomerUserId()).ifPresent(customer -> {
                Notification notification = new Notification();
                notification.setBookingId(booking.getId());
                notification.setChannel("email");
                notification.setRecipient(customer.getEmail());
                notification.setTemplate("booking_cancelled");
                notificationRepository.save(notification);
            });
        }

        return cancellation;
    }

    private void markOpen(Seat seat) {
        seat.setStatus("open");
        seatRepository.save(seat);
    }
}
