package com.bustix.boarding;

import com.bustix.booking.CreateBookingRequest;
import com.bustix.operator.Operator;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/bookings/{id}/seats/{id}/check-in - the Boarding Gate State
 * Machine's "Validation Engine" and "Gate Lockout" rules (my-notes/
 * ethiopian_bus_system_specs.md section 4.1).
 */
class BoardingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void agentChecksInAPassengerWhosePresentedIdMatchesTheOneOnFile() throws Exception {
        Operator operator = createOperator("boarding-op-" + UUID.randomUUID(), "Boarding Co");
        var bus = createBus(operator.getId(), "BD-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        var passenger = new CreateBookingRequest.PassengerSeat(
                seat.getId(), "Test Passenger", null, "EP0543XXX", null, null, null);

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), List.of(passenger), "idem-boarding-1"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/bookings/" + bookingId + "/seats/" + seat.getId() + "/check-in")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("EP0543XXX"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardingStatus").value("boarded"));
    }

    @Test
    void checkInRejectsAPresentedIdThatDoesNotMatchTheOneOnFile() throws Exception {
        Operator operator = createOperator("boarding-mismatch-" + UUID.randomUUID(), "Mismatch Co");
        var bus = createBus(operator.getId(), "BD-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        var passenger = new CreateBookingRequest.PassengerSeat(
                seat.getId(), "Test Passenger", null, "EP0543XXX", null, null, null);

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), List.of(passenger), "idem-boarding-2"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/bookings/" + bookingId + "/seats/" + seat.getId() + "/check-in")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("WRONG-ID"))))
                .andExpect(status().isConflict());
    }

    @Test
    void checkInIsBlockedOnceTheTripHasDeparted() throws Exception {
        Operator operator = createOperator("boarding-closed-" + UUID.randomUUID(), "Closed Co");
        var bus = createBus(operator.getId(), "BD-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        // Already departed - created directly (not via /api/fleet/trips,
        // which would reject a past departureAt) so the boarding-closed
        // path can be exercised without waiting for the scheduler.
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().minus(1, ChronoUnit.HOURS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        var passenger = new CreateBookingRequest.PassengerSeat(
                seat.getId(), "Test Passenger", null, "EP0543XXX", null, null, null);

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), List.of(passenger), "idem-boarding-3"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/bookings/" + bookingId + "/seats/" + seat.getId() + "/check-in")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("EP0543XXX"))))
                .andExpect(status().isConflict());
    }
}
