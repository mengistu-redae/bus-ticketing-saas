package com.bustix.dashboard;

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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/{operator,agent,platform,my}/dashboard - the four role landing
 * pages (DashboardController/DashboardService). Drives real bookings through
 * POST /api/bookings first, then asserts the aggregates and the per-role
 * access gate. Cross-tenant assertions (platform) use >= since the shared
 * class-level Postgres carries rows from every test in this class.
 */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static List<CreateBookingRequest.PassengerSeat> passenger(UUID seatId) {
        return List.of(new CreateBookingRequest.PassengerSeat(seatId, "Dash Passenger", null, null, null));
    }

    private Trip futureTripWithSeats(Operator operator, int seatCount, BigDecimal price) throws Exception {
        var bus = createBus(operator.getId(), "DSH-" + UUID.randomUUID(), seatCount, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Hawassa");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(2, ChronoUnit.DAYS), price);
        for (int i = 0; i < seatCount; i++) {
            createSeat(trip.getId(), "1" + (char) ('A' + i));
        }
        return trip;
    }

    private void book(Operator operator, UUID tripId, UUID seatId, String idemKey) throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(asAgent("dash-agent", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(tripId, passenger(seatId), idemKey))))
                .andExpect(status().isOk());
    }

    @Test
    void operatorDashboardCountsTheOperatorsOwnBookingsFleetAndDepartures() throws Exception {
        Operator operator = createOperator("dash-op-" + UUID.randomUUID(), "Dash Co");
        Trip trip = futureTripWithSeats(operator, 3, new BigDecimal("100.00"));
        List<Seat> seats = seatRepository.findAllByTripId(trip.getId());
        book(operator, trip.getId(), seats.get(0).getId(), "dash-1");
        book(operator, trip.getId(), seats.get(1).getId(), "dash-2");

        mockMvc.perform(get("/api/operator/dashboard")
                        .with(asOperatorAdmin("dash-admin", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("30d"))
                .andExpect(jsonPath("$.bookings.current").value(2))
                .andExpect(jsonPath("$.bookings.previous").value(0))
                .andExpect(jsonPath("$.bookings.deltaPct").value(100.0))
                .andExpect(jsonPath("$.bookings.cancelledCurrent").value(0))
                .andExpect(jsonPath("$.revenue.current").value(230.00)) // 2 x (100 + 15% VAT)
                .andExpect(jsonPath("$.fleet.activeBuses").value(1))
                .andExpect(jsonPath("$.fleet.activeRoutes").value(1))
                .andExpect(jsonPath("$.fleet.upcomingTrips").value(1))
                // 30d window inclusive of today = 30 gap-filled daily points.
                .andExpect(jsonPath("$.series.days.length()").value(30))
                .andExpect(jsonPath("$.series.bookings.length()").value(30))
                .andExpect(jsonPath("$.series.bookings[29]").value(2)) // today's bucket
                .andExpect(jsonPath("$.breakdowns.channel[0].key").value("counter"))
                .andExpect(jsonPath("$.breakdowns.status[0].key").value("confirmed"))
                .andExpect(jsonPath("$.topRoutes[0].routeName").value("Addis Ababa → Hawassa"))
                .andExpect(jsonPath("$.topRoutes[0].bookings").value(2))
                .andExpect(jsonPath("$.occupancy[0].rate").value(greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.recentBookings.length()").value(2))
                .andExpect(jsonPath("$.upcomingDepartures[0].seatsBooked").value(2))
                .andExpect(jsonPath("$.upcomingDepartures[0].capacity").value(3))
                .andExpect(jsonPath("$.upcomingDepartures[0].routeName").value("Addis Ababa → Hawassa"));
    }

    @Test
    void operatorDashboardHonoursThePeriodParam() throws Exception {
        Operator operator = createOperator("dash-period-" + UUID.randomUUID(), "Period Co");

        mockMvc.perform(get("/api/operator/dashboard?period=7d")
                        .with(asOperatorAdmin("dash-period-admin", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("7d"))
                .andExpect(jsonPath("$.series.days.length()").value(7));

        mockMvc.perform(get("/api/operator/dashboard?period=90d")
                        .with(asOperatorAdmin("dash-period-admin", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("90d"))
                .andExpect(jsonPath("$.series.days.length()").value(90));
    }

    @Test
    void operatorDashboardDoesNotLeakAnotherOperatorsActivity() throws Exception {
        Operator mine = createOperator("dash-mine-" + UUID.randomUUID(), "Mine Co");
        Operator other = createOperator("dash-other-" + UUID.randomUUID(), "Other Co");
        Trip otherTrip = futureTripWithSeats(other, 2, new BigDecimal("100.00"));
        book(other, otherTrip.getId(), seatRepository.findAllByTripId(otherTrip.getId()).get(0).getId(), "dash-other-1");

        mockMvc.perform(get("/api/operator/dashboard")
                        .with(asOperatorAdmin("dash-mine-admin", mine.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings.current").value(0))
                .andExpect(jsonPath("$.recentBookings.length()").value(0))
                .andExpect(jsonPath("$.topRoutes.length()").value(0))
                .andExpect(jsonPath("$.fleet.upcomingTrips").value(0));
    }

    @Test
    void agentDashboardLeadsWithTheSignedInAgentsOwnCounterBookings() throws Exception {
        Operator operator = createOperator("dash-agent-op-" + UUID.randomUUID(), "Agent Dash Co");
        Trip trip = futureTripWithSeats(operator, 2, new BigDecimal("100.00"));
        book(operator, trip.getId(), seatRepository.findAllByTripId(trip.getId()).get(0).getId(), "dash-agent-1");

        mockMvc.perform(get("/api/agent/dashboard")
                        .with(asAgent("dash-agent", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myCounterBookings.today").value(1))
                .andExpect(jsonPath("$.operatorBookingsToday").value(1))
                .andExpect(jsonPath("$.pendingCargoRequests").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.sparkline14d.length()").value(14))
                .andExpect(jsonPath("$.sparkline14d[13]").value(1)) // today's counter booking
                .andExpect(jsonPath("$.recentBookings.length()").value(1));
    }

    @Test
    void platformDashboardAggregatesAcrossEveryOperator() throws Exception {
        Operator operator = createOperator("dash-plat-" + UUID.randomUUID(), "Platform Dash Co");
        Trip trip = futureTripWithSeats(operator, 2, new BigDecimal("100.00"));
        book(operator, trip.getId(), seatRepository.findAllByTripId(trip.getId()).get(0).getId(), "dash-plat-1");

        mockMvc.perform(get("/api/platform/dashboard")
                        .with(asPlatformAdmin("dash-platform-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("30d"))
                .andExpect(jsonPath("$.operators.total").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.bookings.current").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.upcomingTrips").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.series.days.length()").value(30))
                .andExpect(jsonPath("$.breakdowns.channel.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.topRoutes.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.topOperators.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void customerDashboardListsTheCustomersOwnUpcomingTrips() throws Exception {
        Operator operator = createOperator("dash-cust-" + UUID.randomUUID(), "Customer Dash Co");
        Trip trip = futureTripWithSeats(operator, 2, new BigDecimal("100.00"));
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("dash-customer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passenger(seat.getId()), "dash-cust-1"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingRef = objectMapper.readTree(bookingJson).get("bookingRef").asText();

        mockMvc.perform(get("/api/my-dashboard").with(asCustomer("dash-customer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.upcoming").value(1))
                .andExpect(jsonPath("$.counts.past").value(0))
                .andExpect(jsonPath("$.counts.cancelled").value(0))
                .andExpect(jsonPath("$.upcomingTrips[0].bookingRef").value(bookingRef))
                .andExpect(jsonPath("$.upcomingTrips[0].routeName").value("Addis Ababa → Hawassa"));
    }

    @Test
    void eachDashboardEndpointIsGatedToItsOwnRole() throws Exception {
        Operator operator = createOperator("dash-gate-" + UUID.randomUUID(), "Gate Co");
        String org = operator.getKeycloakOrgId();

        // wrong role -> 403
        mockMvc.perform(get("/api/operator/dashboard").with(asCustomer("gate-c")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agent/dashboard").with(asOperatorAdmin("gate-oa", org)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/dashboard").with(asAgent("gate-a", org)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/my-dashboard").with(asPlatformAdmin("gate-pa")))
                .andExpect(status().isForbidden());

        // right role -> 200
        mockMvc.perform(get("/api/operator/dashboard").with(asOperatorAdmin("gate-oa", org)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/dashboard").with(asAgent("gate-a", org)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/dashboard").with(asPlatformAdmin("gate-pa")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/my-dashboard").with(asCustomer("gate-c")))
                .andExpect(status().isOk());
    }
}
