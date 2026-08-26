package com.bustix.booking;

import com.bustix.notification.NotificationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/bookings - both channels described atop BookingController
 * (self_service and counter), through the real seat-lock -> DB-write flow
 * (BookingService/BookingWriter) against real Postgres and Redis.
 */
class BookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    /** One passenger seat, named generically - most tests here don't care who the passenger is. */
    private static List<CreateBookingRequest.PassengerSeat> passengers(UUID seatId) {
        return List.of(new CreateBookingRequest.PassengerSeat(seatId, "Test Passenger", null, null, null));
    }

    @Test
    void customerBooksASeatSelfService() throws Exception {
        Operator operator = createOperator("booking-op-" + UUID.randomUUID(), "Booking Co");
        var bus = createBus(operator.getId(), "BK-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        var request = new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-key-1");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("self_service"))
                .andExpect(jsonPath("$.status").value("confirmed"))
                // 120.00 subtotal + 15% VAT (bustix.ticketing.vat-rate) = 138.00 total.
                .andExpect(jsonPath("$.subtotalAmount").value(120.00))
                .andExpect(jsonPath("$.taxAmount").value(18.00))
                .andExpect(jsonPath("$.totalAmount").value(138.00))
                .andExpect(jsonPath("$.ticketNumber").isNotEmpty())
                .andExpect(jsonPath("$.bookingRef").isNotEmpty())
                .andExpect(jsonPath("$.agentUserId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID bookingId = UUID.fromString(objectMapper.readTree(bookingJson).get("id").asText());

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("booked");
        assertThat(notificationRepository.findTop50ByStatusOrderByCreatedAtAsc("pending"))
                .anySatisfy(n -> assertThat(n.getBookingId()).isEqualTo(bookingId));
    }

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheOriginalBookingInsteadOfDoubleBooking() throws Exception {
        Operator operator = createOperator("booking-idem-" + UUID.randomUUID(), "Idem Co");
        var bus = createBus(operator.getId(), "BK-2", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        var request = new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-key-repeat");

        String first = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(first).get("id").asText();
        String secondId = objectMapper.readTree(second).get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(bookingRepository.findAllByTenantId(operator.getId())).hasSize(1);
    }

    @Test
    void bookingAnAlreadyBookedSeatReturnsConflict() throws Exception {
        Operator operator = createOperator("booking-conflict-" + UUID.randomUUID(), "Conflict Co");
        var bus = createBus(operator.getId(), "BK-3", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-a"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-b"))))
                .andExpect(status().isConflict());
    }

    @Test
    void agentBooksOnBehalfOfAWalkInCustomerAtTheCounter() throws Exception {
        Operator operator = createOperator("booking-agent-" + UUID.randomUUID(), "Agent Co");
        var bus = createBus(operator.getId(), "BK-4", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-counter"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("counter"))
                .andExpect(jsonPath("$.agentUserId").exists());
    }

    @Test
    void agentCannotBookATripBelongingToAnotherOperator() throws Exception {
        Operator tripOwner = createOperator("booking-owner-" + UUID.randomUUID(), "Owner Co");
        Operator otherAgentsOperator = createOperator("booking-other-" + UUID.randomUUID(), "Other Co");
        var bus = createBus(tripOwner.getId(), "BK-5", 10, "2x2");
        var route = createRoute(tripOwner.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(tripOwner.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        // Fixed: TenantMismatchException previously had no exception handler
        // and fell through to a 500 despite its own javadoc claiming 403 -
        // see the handler added to BookingController.
        mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", otherAgentsOperator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-mismatch"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookingANonexistentTripReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(UUID.randomUUID(), passengers(UUID.randomUUID()), "idem-missing"))))
                .andExpect(status().isNotFound());
    }

    // --- Operator status enforcement: deactivating an operator
    // (PlatformController.deactivate) blocks new bookings against its trips
    // - booking-time only, deliberately not enforced at search or staff
    // login (see CLAUDE.md's Operator status enforcement note).

    @Test
    void bookingIsBlockedWhenTheTripsOperatorIsDeactivated() throws Exception {
        Operator operator = createOperator("booking-inactive-" + UUID.randomUUID(), "Inactive Co");
        var bus = createBus(operator.getId(), "BK-9", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        operator.setStatus("inactive");
        operatorRepository.save(operator);

        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-inactive"))))
                .andExpect(status().isConflict());

        // The seat was never actually locked/sold - still open for booking
        // once the operator is reactivated.
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void guestBookingIsBlockedWhenTheTripsOperatorIsDeactivated() throws Exception {
        Operator operator = createOperator("booking-inactive-guest-" + UUID.randomUUID(), "Inactive Guest Co");
        var bus = createBus(operator.getId(), "BK-10", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        operator.setStatus("inactive");
        operatorRepository.save(operator);

        var request = new CreateGuestBookingRequest(
                trip.getId(), passengers(seat.getId()), "idem-guest-inactive", "+251911234567", null);

        mockMvc.perform(post("/api/bookings/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingSucceedsAgainOnceADeactivatedOperatorIsReactivated() throws Exception {
        Operator operator = createOperator("booking-reactivated-" + UUID.randomUUID(), "Reactivated Co");
        var bus = createBus(operator.getId(), "BK-11", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        operator.setStatus("inactive");
        operatorRepository.save(operator);
        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-reactivate-1"))))
                .andExpect(status().isConflict());

        operator.setStatus("active");
        operatorRepository.save(operator);
        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-reactivate-2"))))
                .andExpect(status().isOk());
    }

    // --- GET /api/bookings(/{id})(/seats) - staff (agent/operator_admin)
    // tenant-scoped lookups, added alongside the ticketing fields above:
    // previously nothing exposed BookingRepository's tenant-scoped finders
    // at all, so an agent had no way to look up their own operator's
    // bookings through the API.

    @Test
    void agentListsAndViewsTheirOwnOperatorsBookings() throws Exception {
        Operator operator = createOperator("booking-list-" + UUID.randomUUID(), "List Co");
        var bus = createBus(operator.getId(), "BK-6", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-list"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(get("/api/bookings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bookingId));

        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId));

        mockMvc.perform(get("/api/bookings/" + bookingId + "/seats")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seatNo").value("1A"))
                .andExpect(jsonPath("$[0].passengerName").value("Test Passenger"));
    }

    @Test
    void agentCannotViewAnotherOperatorsBooking() throws Exception {
        Operator owner = createOperator("booking-priv-owner-" + UUID.randomUUID(), "Owner Co");
        Operator otherAgentsOperator = createOperator("booking-priv-other-" + UUID.randomUUID(), "Other Co");
        var bus = createBus(owner.getId(), "BK-7", 10, "2x2");
        var route = createRoute(owner.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(owner.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asAgent("agent-owner", owner.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-priv"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .with(asAgent("agent-other", otherAgentsOperator.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    // --- Age-based fares (my-notes/ethiopian_bus_system_specs.md section
    // 4.1): an infant (age < 3) rides free on their seated passenger's lap
    // rather than getting a seat of their own - see BookingInfant's javadoc.

    @Test
    void infantRidesFreeOnAnAdultsLapWithoutConsumingASeparateSeat() throws Exception {
        Operator operator = createOperator("booking-infant-" + UUID.randomUUID(), "Infant Co");
        var bus = createBus(operator.getId(), "BK-8", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");
        Seat untouchedSeat = createSeat(trip.getId(), "1B");

        var passenger = new CreateBookingRequest.PassengerSeat(
                seat.getId(), "Parent Passenger", null, null, null, 30,
                List.of(new CreateBookingRequest.PassengerSeat.Infant("Baby Passenger", 1)));
        var request = new CreateBookingRequest(trip.getId(), List.of(passenger), "idem-infant");

        String bookingJson = mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                // One adult seat's worth, not two - the infant added no fare.
                .andExpect(jsonPath("$.subtotalAmount").value(120.00))
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(bookingJson).get("id").asText();

        // The infant's seat companion stays open - no seat was consumed for them.
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("booked");
        assertThat(seatRepository.findById(untouchedSeat.getId()).orElseThrow().getStatus()).isEqualTo("open");

        mockMvc.perform(get("/api/my-bookings/" + bookingId + "/seats")
                        .with(asCustomer("customer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].passengerAge").value(30))
                .andExpect(jsonPath("$[0].infants[0].name").value("Baby Passenger"))
                .andExpect(jsonPath("$[0].infants[0].age").value(1));
    }
}
