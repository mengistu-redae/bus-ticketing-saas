package com.bustix.scheduling;

import com.bustix.operator.Operator;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/trips/search - the cross-tenant marketplace read path described
 * in CLAUDE.md's "marketplace exception". Exercises RouteRepository's
 * un-tenant-filtered finder for real, through Postgres, not mocked.
 */
class TripSearchIntegrationTest extends AbstractIntegrationTest {

    @Test
    void findsScheduledTripsAcrossOperatorsForTheSameOriginAndDestination() throws Exception {
        Operator operatorA = createOperator("operator-a-" + UUID.randomUUID(), "Operator A");
        Operator operatorB = createOperator("operator-b-" + UUID.randomUUID(), "Operator B");

        var busA = createBus(operatorA.getId(), "AAA-111", 40, "2x2");
        var routeA = createRoute(operatorA.getId(), "Addis Ababa", "Bahir Dar");
        var tripA = createTrip(operatorA.getId(), routeA.getId(), busA.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("500.00"));
        createSeat(tripA.getId(), "1A");
        createSeat(tripA.getId(), "1B");

        var busB = createBus(operatorB.getId(), "BBB-222", 40, "2x2");
        var routeB = createRoute(operatorB.getId(), "Addis Ababa", "Bahir Dar");
        var tripB = createTrip(operatorB.getId(), routeB.getId(), busB.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("450.00"));
        createSeat(tripB.getId(), "1A");

        // A trip on a different route entirely shouldn't show up.
        var unrelatedRoute = createRoute(operatorA.getId(), "Addis Ababa", "Hawassa");
        createTrip(operatorA.getId(), unrelatedRoute.getId(), busA.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), new BigDecimal("300.00"));

        mockMvc.perform(get("/api/trips/search")
                        .param("origin", "Addis Ababa")
                        .param("destination", "Bahir Dar")
                        .with(asCustomer("customer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].operatorName", containsInAnyOrder("Operator A", "Operator B")))
                // Sorted by departureAt ascending - tripA departs first.
                .andExpect(jsonPath("$[0].tripId").value(tripA.getId().toString()))
                .andExpect(jsonPath("$[0].availableSeats").value(2))
                .andExpect(jsonPath("$[1].tripId").value(tripB.getId().toString()))
                .andExpect(jsonPath("$[1].availableSeats").value(1));
    }

    @Test
    void excludesTripsThatDepartedBeforeTheDepartureAfterParam() throws Exception {
        Operator operator = createOperator("operator-past-" + UUID.randomUUID(), "Past Co");
        var bus = createBus(operator.getId(), "PPP-333", 40, "2x2");
        var route = createRoute(operator.getId(), "Gondar", "Mekelle");
        createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.HOURS), new BigDecimal("200.00"));

        mockMvc.perform(get("/api/trips/search")
                        .param("origin", "Gondar")
                        .param("destination", "Mekelle")
                        .param("departureAfter", Instant.now().plus(1, ChronoUnit.DAYS).toString())
                        .with(asCustomer("customer-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void excludesTripsOnASoftDeactivatedRoute() throws Exception {
        Operator operator = createOperator("operator-inactive-route-" + UUID.randomUUID(), "Inactive Route Co");
        var bus = createBus(operator.getId(), "IRC-555", 40, "2x2");
        var route = createRoute(operator.getId(), "Shashemene", "Dilla");
        createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));

        // DELETE /api/fleet/routes/{id} soft-deactivates.
        route.setActive(false);
        routeRepository.save(route);

        mockMvc.perform(get("/api/trips/search")
                        .param("origin", "Shashemene")
                        .param("destination", "Dilla")
                        .with(asCustomer("customer-3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void agentCanAlsoSearchForCounterBookingOnBehalfOfAWalkInCustomer() throws Exception {
        Operator operator = createOperator("operator-agent-" + UUID.randomUUID(), "Agent Co");
        var bus = createBus(operator.getId(), "QQQ-444", 40, "2x2");
        var route = createRoute(operator.getId(), "Jimma", "Nekemte");
        createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("150.00"));

        mockMvc.perform(get("/api/trips/search")
                        .param("origin", "Jimma")
                        .param("destination", "Nekemte")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void operatorAdminCannotUseTheCustomerFacingSearchEndpoint() throws Exception {
        mockMvc.perform(get("/api/trips/search")
                        .param("origin", "Addis Ababa")
                        .param("destination", "Bahir Dar")
                        .with(asOperatorAdmin("admin-1", "some-org")))
                .andExpect(status().isForbidden());
    }
}
