package com.bustix.api.v1;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.operator.Operator;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-2b + WS-3: the partner-facing {@code /v1/bookings} surface - create
 * (channel "partner"), read, cancel, reschedule; tenant-scoped; scope-gated;
 * every write requires an {@code Idempotency-Key} header.
 */
class V1BookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    private Trip seedTrip(Operator operator, String origin, String destination, int seats, String price) {
        var bus = createBus(operator.getId(), "V1B-" + UUID.randomUUID().toString().substring(0, 6), seats, "2x2");
        var route = createRoute(operator.getId(), origin, destination);
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(5, ChronoUnit.DAYS), new BigDecimal(price));
        for (int i = 1; i <= seats; i++) {
            createSeat(trip.getId(), "1" + (char) ('A' + i - 1));
        }
        return trip;
    }

    private String createBookingBody(UUID tripId, UUID seatId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "tripId", tripId.toString(),
                "contactPhone", "+251911234567",
                "passengers", List.of(Map.of("seatId", seatId.toString(), "passengerName", "Test Passenger"))));
    }

    @Test
    void createReadCancelRoundTrip() throws Exception {
        Operator operator = createOperator("v1b-" + UUID.randomUUID(), "V1B Co");
        createApiClient(operator.getId(), "v1b-acme");
        Trip trip = seedTrip(operator, "Addis Ababa", "Jimma", 4, "300.00");
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        String created = mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-acme"))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBookingBody(trip.getId(), seat.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("partner"))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.operatorId").value(operator.getId().toString()))
                .andExpect(jsonPath("$.subtotalAmount").value(300.00))
                .andExpect(jsonPath("$.taxAmount").value(45.00))
                .andExpect(jsonPath("$.totalAmount").value(345.00))
                .andExpect(jsonPath("$.bookingRef").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        Booking persisted = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(persisted.getChannel()).isEqualTo("partner");
        assertThat(persisted.getGuestContactPhone()).isEqualTo("+251911234567");

        mockMvc.perform(get("/v1/bookings").with(asPartner("v1b-acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(bookingId.toString()));

        mockMvc.perform(get("/v1/bookings/{id}/seats", bookingId).with(asPartner("v1b-acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].passengerName").value("Test Passenger"))
                .andExpect(jsonPath("$[0].seatNo").value(seat.getSeatNo()));

        mockMvc.perform(post("/v1/bookings/{id}/cancel", bookingId).with(asPartner("v1b-acme"))
                        .header("Idempotency-Key", "cancel-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.refundAmount").exists());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void writeWithoutAnIdempotencyKeyHeaderIs400() throws Exception {
        Operator operator = createOperator("v1b-nokey-" + UUID.randomUUID(), "NoKey Co");
        createApiClient(operator.getId(), "v1b-nokey");
        Trip trip = seedTrip(operator, "Adama", "Asella", 4, "90.00");
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-nokey"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBookingBody(trip.getId(), seat.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency-key-required"));
    }

    @Test
    void replayingAKeyReturnsTheSameResponseAndDoesNotDoubleBook() throws Exception {
        Operator operator = createOperator("v1b-idem-" + UUID.randomUUID(), "Idem Co");
        createApiClient(operator.getId(), "v1b-idem");
        Trip trip = seedTrip(operator, "Adama", "Dire Dawa", 4, "200.00");
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);
        String body = createBookingBody(trip.getId(), seat.getId());
        String key = "fixed-key-123";

        String first = mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-idem"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-idem"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(first).get("id")).isEqualTo(objectMapper.readTree(second).get("id"));
        assertThat(bookingRepository.findAllByTenantId(operator.getId())).hasSize(1);
    }

    @Test
    void sameKeyWithADifferentBodyIs422() throws Exception {
        Operator operator = createOperator("v1b-422-" + UUID.randomUUID(), "422 Co");
        createApiClient(operator.getId(), "v1b-422");
        Trip trip = seedTrip(operator, "Bahir Dar", "Gondar", 4, "150.00");
        var seats = seatRepository.findAllByTripId(trip.getId());
        String key = "reused-key";

        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-422"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBookingBody(trip.getId(), seats.get(0).getId())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-422"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBookingBody(trip.getId(), seats.get(1).getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));
    }

    @Test
    void cannotBookAnotherOperatorsTrip() throws Exception {
        Operator mine = createOperator("v1b-mine-" + UUID.randomUUID(), "Mine");
        Operator other = createOperator("v1b-other-" + UUID.randomUUID(), "Other");
        createApiClient(mine.getId(), "v1b-mine-acme");
        Trip otherTrip = seedTrip(other, "Bahir Dar", "Gondar", 4, "150.00");
        Seat seat = seatRepository.findAllByTripId(otherTrip.getId()).get(0);

        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-mine-acme"))
                        .header("Idempotency-Key", "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBookingBody(otherTrip.getId(), seat.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant-mismatch"));
    }

    @Test
    void writeEndpointsNeedTheWriteScope() throws Exception {
        Operator operator = createOperator("v1b-scope-" + UUID.randomUUID(), "Scope Co");
        createApiClient(operator.getId(), "v1b-scope");
        Trip trip = seedTrip(operator, "Hawassa", "Shashamane", 4, "80.00");
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        mockMvc.perform(get("/v1/bookings").with(asPartner("v1b-scope", "bookings:read")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-scope", "bookings:read"))
                        .header("Idempotency-Key", "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBookingBody(trip.getId(), seat.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient-scope"));
    }

    @Test
    void invalidContactPhoneIsAProblemJson400() throws Exception {
        Operator operator = createOperator("v1b-phone-" + UUID.randomUUID(), "Phone Co");
        createApiClient(operator.getId(), "v1b-phone");
        Trip trip = seedTrip(operator, "Mekelle", "Adigrat", 4, "120.00");
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        String body = objectMapper.writeValueAsString(Map.of(
                "tripId", trip.getId().toString(),
                "contactPhone", "0911234567",
                "passengers", List.of(Map.of("seatId", seat.getId().toString(), "passengerName", "P"))));

        mockMvc.perform(post("/v1/bookings").with(asPartner("v1b-phone"))
                        .header("Idempotency-Key", "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("contactPhone"));
    }
}
