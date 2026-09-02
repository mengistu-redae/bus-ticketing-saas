package com.bustix.refund;

import com.bustix.booking.Booking;
import com.bustix.booking.BookingRepository;
import com.bustix.booking.CreateBookingRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both cancellation paths (CancellationController/CancellationService):
 * staff-initiated POST /api/bookings/{id}/cancel (tenant-scoped) and
 * customer self-service POST /api/my-bookings/{id}/cancel
 * (ownership-scoped) - including the refund calculation against a real
 * refund_policies row (RefundCalculatorTest already covers
 * RefundCalculator's tier logic in isolation; this exercises it wired up
 * through the full HTTP -> transaction -> seat-release path).
 */
class CancellationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RefundPolicyRepository refundPolicyRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void agentCancelsABookingAndTheSeatIsFreedBackUp() throws Exception {
        Operator operator = createOperator("cancel-op-" + UUID.randomUUID(), "Cancel Co");
        var bus = createBus(operator.getId(), "CX-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        givenRefundPolicy(operator.getId(), null, "[{\"cutoff_hours\":24,\"refund_percent\":100},"
                + "{\"cutoff_hours\":2,\"refund_percent\":50},{\"cutoff_hours\":0,\"refund_percent\":0}]");

        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "cancel-idem-1");

        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("change of plans"))))
                .andExpect(status().isOk())
                // 48h notice clears the 24h/100% tier - full refund of the
                // tax-inclusive total (200.00 fare + 30.00 VAT), not just the fare.
                .andExpect(jsonPath("$.refundAmount").value(230.00))
                .andExpect(jsonPath("$.reason").value("change of plans"));

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void refundIsZeroWhenTheOperatorHasNoRefundPolicyConfigured() throws Exception {
        Operator operator = createOperator("cancel-nopolicy-" + UUID.randomUUID(), "No Policy Co");
        var bus = createBus(operator.getId(), "CX-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        // Deliberately no refund_policies row for this operator.

        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "cancel-idem-nopolicy");

        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(0));
    }

    @Test
    void cancellingAnAlreadyCancelledBookingReturnsConflict() throws Exception {
        Operator operator = createOperator("cancel-twice-" + UUID.randomUUID(), "Twice Co");
        var bus = createBus(operator.getId(), "CX-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "cancel-idem-twice");

        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isConflict());
    }

    @Test
    void agentCannotCancelAnotherOperatorsBooking() throws Exception {
        Operator bookingOwner = createOperator("cancel-owner-" + UUID.randomUUID(), "Owner Co");
        Operator otherOperator = createOperator("cancel-other-" + UUID.randomUUID(), "Other Co");
        var bus = createBus(bookingOwner.getId(), "CX-4", 10, "2x2");
        var route = createRoute(bookingOwner.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(bookingOwner.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "cancel-idem-crosstenant");

        // Tenant-scoped lookup (findByIdAndTenantId) means an agent from a
        // different operator can't even see the booking exists.
        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-2", otherOperator.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCannotUseTheStaffCancelEndpoint() throws Exception {
        Operator operator = createOperator("cancel-customer-" + UUID.randomUUID(), "Customer Co");
        var bus = createBus(operator.getId(), "CX-5", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "cancel-idem-customer");

        // The staff path's tenant-scoped lookup assumes a staff token - a
        // customer has to use POST /api/my-bookings/{id}/cancel instead
        // (see the tests below).
        mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                        .with(asCustomer("customer-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCancelsTheirOwnBookingViaSelfService() throws Exception {
        Operator operator = createOperator("selfcancel-op-" + UUID.randomUUID(), "Self Cancel Co");
        var bus = createBus(operator.getId(), "SC-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        givenRefundPolicy(operator.getId(), null, "[{\"cutoff_hours\":24,\"refund_percent\":100},"
                + "{\"cutoff_hours\":2,\"refund_percent\":50},{\"cutoff_hours\":0,\"refund_percent\":0}]");

        // createBookingViaApi books as customer "customer-for-<idempotencyKey>".
        String idempotencyKey = "selfcancel-idem-1";
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), idempotencyKey);

        mockMvc.perform(post("/api/my-bookings/" + booking.getId() + "/cancel")
                        .with(asCustomer("customer-for-" + idempotencyKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("plans changed"))))
                .andExpect(status().isOk())
                // Full refund of the tax-inclusive total (200.00 fare + 30.00 VAT).
                .andExpect(jsonPath("$.refundAmount").value(230.00))
                .andExpect(jsonPath("$.reason").value("plans changed"));

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void customerCannotCancelAnotherCustomersBookingViaSelfService() throws Exception {
        Operator operator = createOperator("selfcancel-owner-" + UUID.randomUUID(), "Owner Co");
        var bus = createBus(operator.getId(), "SC-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        String idempotencyKey = "selfcancel-idem-crossowner";
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), idempotencyKey);

        // Ownership-scoped lookup (findByIdAndCustomerUserId) means a
        // different customer can't even see the booking exists.
        mockMvc.perform(post("/api/my-bookings/" + booking.getId() + "/cancel")
                        .with(asCustomer("some-other-customer")))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerSelfServiceCancelOnAlreadyCancelledBookingReturnsConflict() throws Exception {
        Operator operator = createOperator("selfcancel-twice-" + UUID.randomUUID(), "Twice Co");
        var bus = createBus(operator.getId(), "SC-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        String idempotencyKey = "selfcancel-idem-twice";
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), idempotencyKey);
        String customerSubject = "customer-for-" + idempotencyKey;

        mockMvc.perform(post("/api/my-bookings/" + booking.getId() + "/cancel")
                        .with(asCustomer(customerSubject)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/my-bookings/" + booking.getId() + "/cancel")
                        .with(asCustomer(customerSubject)))
                .andExpect(status().isConflict());
    }

    @Test
    void agentCannotUseTheCustomerSelfServiceEndpoint() throws Exception {
        Operator operator = createOperator("selfcancel-agent-" + UUID.randomUUID(), "Agent Blocked Co");
        var bus = createBus(operator.getId(), "SC-4", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(48, ChronoUnit.HOURS), new BigDecimal("200.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        Booking booking = createBookingViaApi(trip.getId(), seat.getId(), "selfcancel-idem-agent");

        // /api/my-bookings/{id}/cancel is CUSTOMER-only - an agent has to
        // use the staff endpoint instead, even for their own operator.
        mockMvc.perform(post("/api/my-bookings/" + booking.getId() + "/cancel")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    private void givenRefundPolicy(UUID tenantId, UUID routeId, String rulesJson) {
        RefundPolicy policy = new RefundPolicy();
        policy.setTenantId(tenantId);
        policy.setRouteId(routeId);
        policy.setRules(rulesJson);
        refundPolicyRepository.save(policy);
    }

    private Booking createBookingViaApi(UUID tripId, UUID seatId, String idempotencyKey) throws Exception {
        String json = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-for-" + idempotencyKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(tripId,
                                        List.of(new CreateBookingRequest.PassengerSeat(seatId, "Test Passenger", null, null, null)),
                                        idempotencyKey))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        return bookingRepository.findById(bookingId).orElseThrow();
    }
}
