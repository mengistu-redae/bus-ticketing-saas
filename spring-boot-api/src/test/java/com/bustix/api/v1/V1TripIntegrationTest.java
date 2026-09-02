package com.bustix.api.v1;

import com.bustix.operator.Operator;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-2: the partner-facing {@code /v1} trip surface - DTO responses,
 * {@code PageEnvelope} pagination, tenant-scoped to the calling partner's
 * operator, gated on the {@code trips:read} scope.
 */
class V1TripIntegrationTest extends AbstractIntegrationTest {

    @Test
    void searchReturnsAPageEnvelopeOfTheCallersOwnOperatorsScheduledTrips() throws Exception {
        Operator mine = createOperator("v1-mine-" + UUID.randomUUID(), "V1 Mine");
        Operator other = createOperator("v1-other-" + UUID.randomUUID(), "V1 Other");
        var busMine = createBus(mine.getId(), "V1M-1", 20, "2x2");
        var routeMine = createRoute(mine.getId(), "Addis Ababa", "Bahir Dar");
        createTrip(mine.getId(), routeMine.getId(), busMine.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("500.00"));
        Trip cancelled = createTrip(mine.getId(), routeMine.getId(), busMine.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("500.00"));
        cancelled.setStatus("cancelled");
        tripRepository.save(cancelled);

        var busOther = createBus(other.getId(), "V1O-1", 20, "2x2");
        var routeOther = createRoute(other.getId(), "Addis Ababa", "Bahir Dar");
        createTrip(other.getId(), routeOther.getId(), busOther.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("450.00"));

        createApiClient(mine.getId(), "v1-mine-acme");

        mockMvc.perform(get("/v1/trips")
                        .param("origin", "addis ababa").param("destination", "BAHIR DAR")
                        .with(asPartner("v1-mine-acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].operatorId").value(mine.getId().toString()))
                .andExpect(jsonPath("$.items[0].origin").value("Addis Ababa"))
                .andExpect(jsonPath("$.items[0].price").value(500.00))
                .andExpect(jsonPath("$.items[0].vatRate").exists());
    }

    @Test
    void getOneTripIs404ForAnotherOperatorsTrip() throws Exception {
        Operator mine = createOperator("v1-g-mine-" + UUID.randomUUID(), "G Mine");
        Operator other = createOperator("v1-g-other-" + UUID.randomUUID(), "G Other");
        var bus = createBus(other.getId(), "GO-1", 10, "2x2");
        var route = createRoute(other.getId(), "Adama", "Hawassa");
        Trip otherTrip = createTrip(other.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("200.00"));
        createApiClient(mine.getId(), "v1-g-mine-acme");

        mockMvc.perform(get("/v1/trips/{id}", otherTrip.getId()).with(asPartner("v1-g-mine-acme")))
                .andExpect(status().isNotFound());
    }

    @Test
    void seatsMapIsReturnedForAnOwnedTrip() throws Exception {
        Operator mine = createOperator("v1-s-" + UUID.randomUUID(), "S Co");
        var bus = createBus(mine.getId(), "S-1", 4, "2x2");
        var route = createRoute(mine.getId(), "Dessie", "Kombolcha");
        Trip trip = createTrip(mine.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("60.00"));
        createSeat(trip.getId(), "1A");
        createSeat(trip.getId(), "1B");
        createApiClient(mine.getId(), "v1-s-acme");

        mockMvc.perform(get("/v1/trips/{id}/seats", trip.getId()).with(asPartner("v1-s-acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].seatNo").value("1A"))
                .andExpect(jsonPath("$[0].status").value("open"));
    }

    @Test
    void aPartnerWithoutTheTripsReadScopeIsForbidden() throws Exception {
        Operator mine = createOperator("v1-noscope-" + UUID.randomUUID(), "NoScope Co");
        createApiClient(mine.getId(), "v1-noscope-acme");

        mockMvc.perform(get("/v1/trips").param("origin", "A").param("destination", "B")
                        .with(asPartner("v1-noscope-acme", "bookings:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHumanStaffTokenCannotReachV1() throws Exception {
        Operator operator = createOperator("v1-human-" + UUID.randomUUID(), "Human Co");

        mockMvc.perform(get("/v1/trips").param("origin", "A").param("destination", "B")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
