package com.bustix.booking;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/(my-)bookings/{id}/reschedule - my-notes/
 * ethiopian_bus_system_specs.md section 5.3. v1 supports single-seat
 * bookings only - see BookingRescheduleService's javadoc.
 */
class BookingRescheduleIntegrationTest extends AbstractIntegrationTest {

    private static List<CreateBookingRequest.PassengerSeat> onePassenger(UUID seatId) {
        return List.of(new CreateBookingRequest.PassengerSeat(seatId, "Test Passenger", null, null, null));
    }

    @Test
    void customerReschedulesToADifferentTripWithTheFlatSelfServiceFee() throws Exception {
        Operator operator = createOperator("reschedule-op-" + UUID.randomUUID(), "Reschedule Co");
        var bus = createBus(operator.getId(), "RS-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip oldTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat oldSeat = createSeat(oldTrip.getId(), "1A");
        Trip newTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("150.00"));
        Seat newSeat = createSeat(newTrip.getId(), "1A");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(oldTrip.getId(), onePassenger(oldSeat.getId()), "idem-reschedule-1"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/my-bookings/" + bookingId + "/reschedule")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RescheduleBookingRequest(newTrip.getId(), newSeat.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(newTrip.getId().toString()))
                // 150.00 subtotal + 22.50 VAT + 50.00 self-service mutation fee.
                .andExpect(jsonPath("$.subtotalAmount").value(150.00))
                .andExpect(jsonPath("$.taxAmount").value(22.50))
                .andExpect(jsonPath("$.rescheduleFee").value(50.00))
                .andExpect(jsonPath("$.totalAmount").value(222.50));

        assertThat(seatRepository.findById(oldSeat.getId()).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(seatRepository.findById(newSeat.getId()).orElseThrow().getStatus()).isEqualTo("booked");
    }

    @Test
    void reschedulingLessThan12HoursBeforeDepartureIsBlocked() throws Exception {
        Operator operator = createOperator("reschedule-late-" + UUID.randomUUID(), "Late Co");
        var bus = createBus(operator.getId(), "RS-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip oldTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(6, ChronoUnit.HOURS), new BigDecimal("120.00"));
        Seat oldSeat = createSeat(oldTrip.getId(), "1A");
        Trip newTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat newSeat = createSeat(newTrip.getId(), "1A");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(oldTrip.getId(), onePassenger(oldSeat.getId()), "idem-reschedule-late"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/my-bookings/" + bookingId + "/reschedule")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RescheduleBookingRequest(newTrip.getId(), newSeat.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void reschedulingAMultiSeatBookingIsBlocked() throws Exception {
        Operator operator = createOperator("reschedule-multi-" + UUID.randomUUID(), "Multi Co");
        var bus = createBus(operator.getId(), "RS-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip oldTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seatA = createSeat(oldTrip.getId(), "1A");
        Seat seatB = createSeat(oldTrip.getId(), "1B");
        Trip newTrip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat newSeat = createSeat(newTrip.getId(), "1A");

        var passengers = List.of(
                new CreateBookingRequest.PassengerSeat(seatA.getId(), "Passenger A", null, null, null),
                new CreateBookingRequest.PassengerSeat(seatB.getId(), "Passenger B", null, null, null));
        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(oldTrip.getId(), passengers, "idem-reschedule-multi"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(post("/api/my-bookings/" + bookingId + "/reschedule")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RescheduleBookingRequest(newTrip.getId(), newSeat.getId()))))
                .andExpect(status().isConflict());
    }
}
