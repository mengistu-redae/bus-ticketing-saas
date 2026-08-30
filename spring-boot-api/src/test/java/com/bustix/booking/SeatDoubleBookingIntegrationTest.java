package com.bustix.booking;

import com.bustix.operator.Operator;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Redis seat lock (SeatLockService) is only a fast-path guard against
 * concurrent bookings - the correctness backstop is the
 * {@code SELECT ... FOR UPDATE} + {@code status = 'open'} re-check in
 * BookingWriter (SeatRepository.findByIdAndTripId is
 * {@code @Lock(PESSIMISTIC_WRITE)}). This test neuters the Redis lock so
 * every acquire "succeeds", proving the DB alone still refuses to sell the
 * same seat twice.
 */
class SeatDoubleBookingIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private SeatLockService seatLockService;

    @BeforeEach
    void redisLockIsANoOp() {
        when(seatLockService.tryAcquire(anyString(), anyString())).thenReturn(true);
    }

    private static List<CreateBookingRequest.PassengerSeat> passengers(UUID seatId) {
        return List.of(new CreateBookingRequest.PassengerSeat(seatId, "Test Passenger", null, null, null));
    }

    @Test
    void theDbRefusesASecondBookingOfTheSameSeatEvenWithTheRedisLockDisabled() throws Exception {
        Operator operator = createOperator("double-book-" + UUID.randomUUID(), "Double Book Co");
        var bus = createBus(operator.getId(), "DB-1", 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        Seat seat = createSeat(trip.getId(), "1A");

        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-db-1"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/bookings")
                        .with(asCustomer("customer-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBookingRequest(trip.getId(), passengers(seat.getId()), "idem-db-2"))))
                .andExpect(status().isConflict());

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo("booked");
    }
}
