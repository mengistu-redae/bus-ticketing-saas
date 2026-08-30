package com.bustix.tenant;

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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The consolidated cross-operator sweep: for every staff-scoped resource,
 * operator A seeds it and operator B's agent/admin is refused
 * (404 "not found" / 403 "tenant mismatch", never 200 + data). The narrower
 * per-feature tests (FleetIntegrationTest, BookingIntegrationTest, ...) keep
 * their own focused cases; this class proves the invariant holds end to end.
 *
 * Also covers the two isolation hardenings from 2026-08-27: a deactivated
 * operator's staff are locked out of the whole API, and a customer's cargo
 * "requested" waybill is only visible to the operator it was routed to.
 */
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private Operator operatorA;
    private Operator operatorB;

    private String orgA() {
        return operatorA.getKeycloakOrgId();
    }

    private String orgB() {
        return operatorB.getKeycloakOrgId();
    }

    private void twoOperators() {
        operatorA = createOperator("iso-a-" + UUID.randomUUID(), "Operator A");
        operatorB = createOperator("iso-b-" + UUID.randomUUID(), "Operator B");
    }

    /** A full A-owned stack: bus, route, future trip with 2 seats, a confirmed booking on seat 0. */
    private record Stack(Trip trip, List<Seat> seats, String bookingId) {
    }

    private Stack seedStackForA() throws Exception {
        var bus = createBus(operatorA.getId(), "ISO-" + UUID.randomUUID(), 4, "2x2");
        var route = createRoute(operatorA.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operatorA.getId(), route.getId(), bus.getId(),
                Instant.now().plus(5, ChronoUnit.DAYS), new BigDecimal("100.00"));
        createSeat(trip.getId(), "1A");
        createSeat(trip.getId(), "1B");
        List<Seat> seats = seatRepository.findAllByTripId(trip.getId());

        var passengers = List.of(new CreateBookingRequest.PassengerSeat(
                seats.get(0).getId(), "A Passenger", null, "ID-A-1", null));
        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("iso-a-agent", orgA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers, "iso-idem-" + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Stack(trip, seats, objectMapper.readTree(bookingJson).get("id").asText());
    }

    // ---- Fleet ----

    @Test
    void operatorBCannotTouchOperatorAsFleet() throws Exception {
        twoOperators();
        var bus = createBus(operatorA.getId(), "A-BUS", 10, "2x2");
        var route = createRoute(operatorA.getId(), "Bahir Dar", "Gondar");
        var trip = createTrip(operatorA.getId(), route.getId(), bus.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("200.00"));

        for (String path : List.of("/api/fleet/buses/" + bus.getId(),
                "/api/fleet/routes/" + route.getId(),
                "/api/fleet/trips/" + trip.getId())) {
            mockMvc.perform(get(path).with(asOperatorAdmin("iso-b-admin", orgB())))
                    .andExpect(status().isNotFound());
            mockMvc.perform(patch(path).with(asOperatorAdmin("iso-b-admin", orgB()))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(path).with(asOperatorAdmin("iso-b-admin", orgB())))
                    .andExpect(status().isNotFound());
        }

        // ...and B's fleet listing shows none of A's rows.
        mockMvc.perform(get("/api/fleet/buses").with(asOperatorAdmin("iso-b-admin", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- Bookings, seats, cancel, reschedule, payments, boarding ----

    @Test
    void operatorBCannotSeeOrActOnOperatorAsBooking() throws Exception {
        twoOperators();
        Stack a = seedStackForA();
        String b = a.bookingId();

        mockMvc.perform(get("/api/bookings/" + b).with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/" + b + "/seats").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/bookings/" + b + "/cancel").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/bookings/" + b + "/reschedule").with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newTripId\":\"" + UUID.randomUUID() + "\",\"newSeatId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        // B's tenant-scoped booking list is empty.
        mockMvc.perform(get("/api/bookings").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void operatorBCannotRecordAPaymentAgainstOperatorAsBooking() throws Exception {
        twoOperators();
        Stack a = seedStackForA();

        mockMvc.perform(post("/api/bookings/" + a.bookingId() + "/payments").with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00,\"method\":\"cash\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bookings/" + a.bookingId() + "/payments").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorBCannotCheckInAPassengerOnOperatorAsTrip() throws Exception {
        twoOperators();
        Stack a = seedStackForA();

        mockMvc.perform(post("/api/bookings/" + a.bookingId() + "/seats/" + a.seats().get(0).getId() + "/check-in")
                        .with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presentedIdNumber\":\"ID-A-1\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- Cargo rate / refund policy config ----

    @Test
    void operatorBCannotTouchOperatorAsRateAndRefundConfig() throws Exception {
        twoOperators();

        String rateJson = mockMvc.perform(post("/api/fleet/cargo-rates").with(asOperatorAdmin("iso-a-admin", orgA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseFreightCharge\":200.00}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rateId = objectMapper.readTree(rateJson).get("id").asText();

        String policyJson = mockMvc.perform(post("/api/fleet/refund-policies").with(asOperatorAdmin("iso-a-admin", orgA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tiers\":[{\"cutoff_hours\":24,\"refund_percent\":100}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policyId = objectMapper.readTree(policyJson).get("id").asText();

        for (String path : List.of("/api/fleet/cargo-rates/" + rateId, "/api/fleet/refund-policies/" + policyId)) {
            mockMvc.perform(get(path).with(asOperatorAdmin("iso-b-admin", orgB()))).andExpect(status().isNotFound());
            mockMvc.perform(patch(path).with(asOperatorAdmin("iso-b-admin", orgB()))
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
            mockMvc.perform(delete(path).with(asOperatorAdmin("iso-b-admin", orgB()))).andExpect(status().isNotFound());
        }
    }

    @Test
    void aRouteSpecificRateOrPolicyMustReferenceTheCallersOwnRoute() throws Exception {
        twoOperators();
        var aRoute = createRoute(operatorA.getId(), "Jimma", "Bonga");

        // Operator B trying to reference operator A's route id -> 404.
        mockMvc.perform(post("/api/fleet/cargo-rates").with(asOperatorAdmin("iso-b-admin", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + aRoute.getId() + "\",\"baseFreightCharge\":200.00}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/fleet/refund-policies").with(asOperatorAdmin("iso-b-admin", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + aRoute.getId() + "\",\"tiers\":[{\"cutoff_hours\":24,\"refund_percent\":100}]}"))
                .andExpect(status().isNotFound());
    }

    // ---- Per-operator settings (singleton, tenant-scoped) ----

    @Test
    void oneOperatorsSettingsAreInvisibleToAndUnaffectedByAnother() throws Exception {
        twoOperators();

        // A overrides its VAT rate.
        mockMvc.perform(patch("/api/fleet/settings").with(asOperatorAdmin("iso-a-admin", orgA()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vatRate\":0.07}"))
                .andExpect(status().isOk());

        // B still sees the platform default, not A's override.
        mockMvc.perform(get("/api/fleet/settings").with(asOperatorAdmin("iso-b-admin", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides").doesNotExist())
                .andExpect(jsonPath("$.effective.vatRate").value(0.15));

        // B changing its own settings doesn't touch A's.
        mockMvc.perform(patch("/api/fleet/settings").with(asOperatorAdmin("iso-b-admin", orgB()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vatRate\":0.20}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/fleet/settings").with(asOperatorAdmin("iso-a-admin", orgA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.vatRate").value(0.07));
    }

    // ---- Cargo waybill lifecycle ----

    @Test
    void operatorBCannotTouchOperatorAsWaybill() throws Exception {
        twoOperators();
        Stack a = seedStackForA();
        // A cargo rate so the waybill can be priced/created.
        mockMvc.perform(post("/api/fleet/cargo-rates").with(asOperatorAdmin("iso-a-admin", orgA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseFreightCharge\":200.00}"))
                .andExpect(status().isOk());

        String waybillJson = mockMvc.perform(post("/api/cargo/waybills").with(asAgent("iso-a-agent", orgA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tripId": "%s",
                                  "consignorName": "A Sender", "consignorPhone": "+251911111111",
                                  "consigneeName": "A Recipient", "consigneePhone": "+251922222222",
                                  "consigneeIdNumber": "CID-A",
                                  "items": [{"description": "box", "grossWeightKg": 5.0}]
                                }
                                """.formatted(a.trip().getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(waybillJson).get("waybill").get("id").asText();

        for (String action : List.of("dispatch", "arrive", "cancel")) {
            mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/" + action).with(asAgent("iso-b-agent", orgB())))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/cargo/waybills/" + waybillId).with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/cargo/waybills/" + waybillId).with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentStatus\":\"paid\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/payments").with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/cargo/waybills").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- Cargo request routing (2026-08-27) ----

    @Test
    void aCustomerRequestIsOnlyVisibleToTheOperatorItWasRoutedTo() throws Exception {
        twoOperators();

        String reqJson = mockMvc.perform(post("/api/my-shipments").with(asCustomer("iso-cust"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "%s",
                                  "consignorName": "Cust", "consignorPhone": "+251911111111",
                                  "consigneeName": "Rcpt", "consigneePhone": "+251922222222",
                                  "items": [{"description": "bag", "grossWeightKg": 8.0}]
                                }
                                """.formatted(operatorA.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(reqJson).get("waybill").get("id").asText();

        // Operator A sees it; operator B does not.
        mockMvc.perform(get("/api/cargo/requests").with(asAgent("iso-a-agent", orgA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.waybill.id=='" + waybillId + "')]").exists());
        mockMvc.perform(get("/api/cargo/requests").with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.waybill.id=='" + waybillId + "')]").doesNotExist());

        // Operator B cannot view or confirm-and-issue it.
        mockMvc.perform(get("/api/cargo/waybills/" + waybillId).with(asAgent("iso-b-agent", orgB())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/confirm-and-issue").with(asAgent("iso-b-agent", orgB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"" + UUID.randomUUID() + "\",\"consigneeIdNumber\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- Dashboards ----

    @Test
    void anOperatorDashboardShowsNoneOfAnotherOperatorsActivity() throws Exception {
        twoOperators();
        seedStackForA(); // one booking under A

        mockMvc.perform(get("/api/operator/dashboard").with(asOperatorAdmin("iso-b-admin", orgB())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings.current").value(0))
                .andExpect(jsonPath("$.recentBookings", hasSize(0)))
                .andExpect(jsonPath("$.topRoutes", hasSize(0)))
                .andExpect(jsonPath("$.fleet.upcomingTrips").value(0));
    }

    // ---- Deactivated-operator lockout (2026-08-27) ----

    @Test
    void aDeactivatedOperatorsStaffAreLockedOutOfTheWholeApi() throws Exception {
        Operator op = createOperator("iso-deact-" + UUID.randomUUID(), "Deactivated Co");
        op.setStatus("inactive");
        operatorRepository.save(op);

        for (String path : List.of("/api/operator/dashboard", "/api/agent/dashboard",
                "/api/fleet/buses", "/api/bookings", "/api/cargo/waybills", "/api/cargo/requests")) {
            mockMvc.perform(get(path).with(asAgent("deact-agent", op.getKeycloakOrgId())))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).with(asOperatorAdmin("deact-admin", op.getKeycloakOrgId())))
                    .andExpect(status().isForbidden());
        }

        // A customer (no org claim) is unaffected - the marketplace still works.
        mockMvc.perform(get("/api/trips/search?origin=Nowhere&destination=Elsewhere"))
                .andExpect(status().isOk());

        // Reactivating restores access.
        op.setStatus("active");
        operatorRepository.save(op);
        mockMvc.perform(get("/api/fleet/buses").with(asOperatorAdmin("deact-admin", op.getKeycloakOrgId())))
                .andExpect(status().isOk());
    }
}
