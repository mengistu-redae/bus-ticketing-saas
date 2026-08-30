package com.bustix.scheduling;

import com.bustix.booking.CreateBookingRequest;
import com.bustix.notification.Notification;
import com.bustix.notification.NotificationRepository;
import com.bustix.operator.Operator;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/fleet/trips/{id} changing a trip's departure/arrival time
 * queues a {@code trip_rescheduled} notification for every confirmed
 * booking on the trip - gated on the operator's
 * {@code reschedule_notifications_enabled} setting (TripUpdateService).
 */
class TripRescheduleNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private long tripRescheduledCount() {
        return notificationRepository.findAll().stream()
                .map(Notification::getTemplate)
                .filter("trip_rescheduled"::equals)
                .count();
    }

    private void bookASeat(Trip trip, UUID seatId, String idem) throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-notify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBookingRequest(
                                trip.getId(),
                                List.of(new CreateBookingRequest.PassengerSeat(seatId, "Notify Passenger", null, null, null)),
                                idem))))
                .andExpect(status().isOk());
    }

    @Test
    void changingDepartureTimeNotifiesConfirmedCustomersWhenEnabled() throws Exception {
        Operator operator = createOperator("trip-notify-on-" + UUID.randomUUID(), "Notify On Co");
        var bus = createBus(operator.getId(), "TN-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(5, ChronoUnit.DAYS), new BigDecimal("100.00"));
        var seat = createSeat(trip.getId(), "1A");

        bookASeat(trip, seat.getId(), "idem-notify-on");

        long before = tripRescheduledCount();

        mockMvc.perform(patch("/api/fleet/trips/" + trip.getId())
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureAt\":\"" + Instant.now().plus(6, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isOk());

        assertThat(tripRescheduledCount()).isEqualTo(before + 1);
    }

    @Test
    void changingDepartureTimeNotifiesNobodyWhenDisabled() throws Exception {
        Operator operator = createOperator("trip-notify-off-" + UUID.randomUUID(), "Notify Off Co");
        var bus = createBus(operator.getId(), "TN-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(5, ChronoUnit.DAYS), new BigDecimal("100.00"));
        var seat = createSeat(trip.getId(), "1A");

        bookASeat(trip, seat.getId(), "idem-notify-off");

        // turn the toggle off
        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rescheduleNotificationsEnabled\":false}"))
                .andExpect(status().isOk());

        long before = tripRescheduledCount();

        mockMvc.perform(patch("/api/fleet/trips/" + trip.getId())
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureAt\":\"" + Instant.now().plus(6, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isOk());

        assertThat(tripRescheduledCount()).isEqualTo(before);
    }

    @Test
    void priceOnlyEditNotifiesNobody() throws Exception {
        Operator operator = createOperator("trip-notify-price-" + UUID.randomUUID(), "Price Co");
        var bus = createBus(operator.getId(), "TN-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(5, ChronoUnit.DAYS), new BigDecimal("100.00"));
        var seat = createSeat(trip.getId(), "1A");

        bookASeat(trip, seat.getId(), "idem-notify-price");

        long before = tripRescheduledCount();

        mockMvc.perform(patch("/api/fleet/trips/" + trip.getId())
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":123.45}"))
                .andExpect(status().isOk());

        assertThat(tripRescheduledCount()).isEqualTo(before);
    }
}
