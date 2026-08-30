package com.bustix.fleet;

import com.bustix.operator.Operator;
import com.bustix.scheduling.CreateTripRequest;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff-facing fleet management (BusController/RouteController/
 * TripController's /api/fleet/* endpoints) - tenant-scoped throughout, as
 * opposed to TripSearchIntegrationTest's cross-tenant marketplace read.
 */
class FleetIntegrationTest extends AbstractIntegrationTest {

    @Test
    void operatorAdminCreatesABusScopedToTheirOwnTenant() throws Exception {
        Operator operator = createOperator("fleet-op-" + UUID.randomUUID(), "Fleet Co");

        mockMvc.perform(post("/api/fleet/buses")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBusRequest("ET-1234", 32, "2x2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(operator.getId().toString()))
                .andExpect(jsonPath("$.plateNo").value("ET-1234"))
                .andExpect(jsonPath("$.capacity").value(32));
    }

    @Test
    void busListingIsScopedToTheCallingOperatorOnly() throws Exception {
        Operator operatorA = createOperator("fleet-a-" + UUID.randomUUID(), "Fleet A");
        Operator operatorB = createOperator("fleet-b-" + UUID.randomUUID(), "Fleet B");
        createBus(operatorA.getId(), "A-BUS-1", 30, "2x2");
        createBus(operatorB.getId(), "B-BUS-1", 30, "2x2");

        mockMvc.perform(get("/api/fleet/buses")
                        .with(asOperatorAdmin("admin-a", operatorA.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].plateNo").value("A-BUS-1"));
    }

    @Test
    void customerCannotManageFleetData() throws Exception {
        mockMvc.perform(post("/api/fleet/buses")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBusRequest("X-0000", 10, "2x2"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsARouteWithOptionalDistanceOmitted() throws Exception {
        Operator operator = createOperator("fleet-route-" + UUID.randomUUID(), "Route Co");

        mockMvc.perform(post("/api/fleet/routes")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRouteRequest("Adama", "Dire Dawa", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("Adama"))
                .andExpect(jsonPath("$.destination").value("Dire Dawa"))
                .andExpect(jsonPath("$.distanceKm").doesNotExist());
    }

    @Test
    void creatingATripGeneratesSeatsFromTheBusCapacityAndLayout() throws Exception {
        Operator operator = createOperator("fleet-trip-" + UUID.randomUUID(), "Trip Co");
        var bus = createBus(operator.getId(), "T-9999", 8, "2x2");
        var route = createRoute(operator.getId(), "Arba Minch", "Sodo");

        CreateTripRequest request = new CreateTripRequest(
                route.getId(), bus.getId(), Instant.now().plus(1, ChronoUnit.DAYS), null, new BigDecimal("250.00"));

        String tripJson = mockMvc.perform(post("/api/fleet/trips")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(operator.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        UUID tripId = UUID.fromString(objectMapper.readTree(tripJson).get("id").asText());
        // "2x2" over capacity 8 -> two full rows: 1A,1B,1C,1D,2A,2B,2C,2D.
        assertThat(seatRepository.findAllByTripId(tripId)).hasSize(8);
    }

    // --- GET /api/fleet/trips/manage - the operator "Trips" page list:
    // denormalized, filtered (upcoming|all|cancelled), route-filterable, paged.

    @Test
    void manageListUpcomingBucketExcludesCancelledPastAndOtherOperatorsTrips() throws Exception {
        Operator operatorA = createOperator("manage-a-" + UUID.randomUUID(), "Manage A");
        Operator operatorB = createOperator("manage-b-" + UUID.randomUUID(), "Manage B");
        var busA = createBus(operatorA.getId(), "MA-1", 40, "2x2");
        var routeA = createRoute(operatorA.getId(), "Addis Ababa", "Gondar");

        createTrip(operatorA.getId(), routeA.getId(), busA.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("300.00"));   // upcoming
        Trip past = createTrip(operatorA.getId(), routeA.getId(), busA.getId(),
                Instant.now().minus(2, ChronoUnit.DAYS), new BigDecimal("300.00"));  // departed
        Trip cancelled = createTrip(operatorA.getId(), routeA.getId(), busA.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("300.00"));
        cancelled.setStatus("cancelled");
        tripRepository.save(cancelled);

        var busB = createBus(operatorB.getId(), "MB-1", 40, "2x2");
        var routeB = createRoute(operatorB.getId(), "Addis Ababa", "Gondar");
        createTrip(operatorB.getId(), routeB.getId(), busB.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("280.00"));

        mockMvc.perform(get("/api/fleet/trips/manage")
                        .with(asOperatorAdmin("admin-a", operatorA.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].origin").value("Addis Ababa"))
                .andExpect(jsonPath("$[0].destination").value("Gondar"))
                .andExpect(jsonPath("$[0].busPlateNo").value("MA-1"))
                .andExpect(jsonPath("$[0].busCapacity").value(40))
                .andExpect(jsonPath("$[0].status").value("scheduled"));

        assertThat(past.getId()).isNotNull();
    }

    @Test
    void manageListCancelledBucketAndRouteFilter() throws Exception {
        Operator operator = createOperator("manage-c-" + UUID.randomUUID(), "Manage C");
        var bus = createBus(operator.getId(), "MC-1", 20, "2x2");
        var route1 = createRoute(operator.getId(), "Adama", "Hawassa");
        var route2 = createRoute(operator.getId(), "Adama", "Jimma");
        createTrip(operator.getId(), route1.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("150.00"));
        Trip c = createTrip(operator.getId(), route2.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("200.00"));
        c.setStatus("cancelled");
        tripRepository.save(c);

        mockMvc.perform(get("/api/fleet/trips/manage").param("status", "cancelled")
                        .with(asOperatorAdmin("admin-c", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].destination").value("Jimma"));

        mockMvc.perform(get("/api/fleet/trips/manage")
                        .param("status", "all").param("routeId", route1.getId().toString())
                        .with(asOperatorAdmin("admin-c", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].destination").value("Hawassa"));
    }

    @Test
    void manageListReportsSeatOccupancy() throws Exception {
        Operator operator = createOperator("manage-occ-" + UUID.randomUUID(), "Manage Occ");
        var bus = createBus(operator.getId(), "MO-1", 4, "2x2");
        var route = createRoute(operator.getId(), "Bahir Dar", "Gondar");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("100.00"));
        createSeat(trip.getId(), "1A");
        createSeat(trip.getId(), "1B");
        createSeat(trip.getId(), "1C");
        Seat booked = createSeat(trip.getId(), "1D");
        booked.setStatus("booked");
        seatRepository.save(booked);

        mockMvc.perform(get("/api/fleet/trips/manage")
                        .with(asOperatorAdmin("admin-occ", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].busCapacity").value(4))
                .andExpect(jsonPath("$[0].availableSeats").value(3))
                .andExpect(jsonPath("$[0].bookedSeats").value(1));
    }

    @Test
    void manageListIsOperatorAdminOnly() throws Exception {
        Operator operator = createOperator("manage-role-" + UUID.randomUUID(), "Manage Role");
        mockMvc.perform(get("/api/fleet/trips/manage")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingATripWithArrivalBeforeDepartureIsRejected() throws Exception {
        Operator operator = createOperator("bad-trip-" + UUID.randomUUID(), "Bad Trip Co");
        var bus = createBus(operator.getId(), "BT-1", 8, "2x2");
        var route = createRoute(operator.getId(), "Dessie", "Kombolcha");
        Instant depart = Instant.now().plus(1, ChronoUnit.DAYS);

        CreateTripRequest request = new CreateTripRequest(
                route.getId(), bus.getId(), depart, depart.minus(1, ChronoUnit.HOURS), new BigDecimal("90.00"));

        mockMvc.perform(post("/api/fleet/trips")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tripListingDoesNotLeakAnotherOperatorsTrips() throws Exception {
        Operator operatorA = createOperator("fleet-trip-a-" + UUID.randomUUID(), "Trip A");
        Operator operatorB = createOperator("fleet-trip-b-" + UUID.randomUUID(), "Trip B");
        var busA = createBus(operatorA.getId(), "A-1", 10, "2x2");
        var routeA = createRoute(operatorA.getId(), "Harar", "Dire Dawa");
        createTrip(operatorA.getId(), routeA.getId(), busA.getId(), Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("100.00"));

        var busB = createBus(operatorB.getId(), "B-1", 10, "2x2");
        var routeB = createRoute(operatorB.getId(), "Harar", "Dire Dawa");
        createTrip(operatorB.getId(), routeB.getId(), busB.getId(), Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("100.00"));

        mockMvc.perform(get("/api/fleet/trips")
                        .with(asOperatorAdmin("admin-b", operatorB.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tenantId").value(operatorB.getId().toString()));
    }
}
