package com.bustix.scheduling;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.notification.Notification;
import com.bustix.notification.NotificationRepository;
import com.bustix.operator.OperatorSettingsService;
import com.bustix.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * The write side of {@code PATCH /api/fleet/trips/{id}}, split into its own
 * {@code @Transactional} bean (same self-invocation/proxy reasoning as
 * {@code BookingWriter} / {@code CancellationService}) so the trip edit and
 * the notification-cascade writes commit together.
 *
 * When a trip's {@code departureAt} or {@code arrivalAt} actually changes,
 * every still-confirmed booking on that trip gets a {@code trip_rescheduled}
 * outbox notification - gated on the operator's
 * {@code reschedule_notifications_enabled} setting (default on), the same
 * switch that governs the per-booking {@code booking_rescheduled} notice in
 * {@code BookingRescheduleService}. Guest bookings (no {@code customerUserId})
 * are skipped - no email on file, identical guard to the rest of the app's
 * notification writes. Price-only edits and the {@code DELETE} cancel path
 * do not notify.
 */
@Service
public class TripUpdateService {

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationRepository notificationRepository;
    private final OperatorSettingsService operatorSettingsService;

    public TripUpdateService(
            TripRepository tripRepository,
            BookingRepository bookingRepository,
            AppUserRepository appUserRepository,
            NotificationRepository notificationRepository,
            OperatorSettingsService operatorSettingsService) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.appUserRepository = appUserRepository;
        this.notificationRepository = notificationRepository;
        this.operatorSettingsService = operatorSettingsService;
    }

    @Transactional
    public Trip update(UUID tripId, UUID tenantId, UpdateTripRequest request) {
        Trip trip = tripRepository.findByIdAndTenantId(tripId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));

        Instant oldDepartureAt = trip.getDepartureAt();
        Instant oldArrivalAt = trip.getArrivalAt();

        if (request.departureAt() != null) {
            trip.setDepartureAt(request.departureAt());
        }
        if (request.arrivalAt() != null) {
            trip.setArrivalAt(request.arrivalAt());
        }
        if (request.price() != null) {
            trip.setPrice(request.price());
        }
        Trip saved = tripRepository.save(trip);

        boolean timeChanged = !Objects.equals(oldDepartureAt, saved.getDepartureAt())
                || !Objects.equals(oldArrivalAt, saved.getArrivalAt());
        if (timeChanged && operatorSettingsService.resolve(tenantId).rescheduleNotificationsEnabled()) {
            notifyBookedCustomers(saved.getId());
        }
        return saved;
    }

    private void notifyBookedCustomers(UUID tripId) {
        for (Booking booking : bookingRepository.findAllByTripIdAndStatus(tripId, "confirmed")) {
            if (booking.getCustomerUserId() == null) {
                continue; // guest booking - no email on file, same guard as elsewhere
            }
            appUserRepository.findById(booking.getCustomerUserId()).ifPresent(customer -> {
                Notification notification = new Notification();
                notification.setBookingId(booking.getId());
                notification.setChannel("email");
                notification.setRecipient(customer.getEmail());
                notification.setTemplate("trip_rescheduled");
                notificationRepository.save(notification);
            });
        }
    }
}
