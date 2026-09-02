package com.bustix.api.v1.webhook;

import com.bustix.operator.Operator;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-5: a partner registers a webhook, a domain event queues a delivery,
 * and the dispatcher signs and POSTs it.
 */
class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDispatcher dispatcher;

    private static String[] webhookScopes() {
        return new String[] {"trips:read", "bookings:read", "bookings:write", "webhooks:manage"};
    }

    private Trip seedBookableTrip(Operator operator) {
        var bus = createBus(operator.getId(), "WH-" + UUID.randomUUID().toString().substring(0, 6), 4, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Jimma");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(4, ChronoUnit.DAYS), new BigDecimal("300.00"));
        createSeat(trip.getId(), "1A");
        return trip;
    }

    @Test
    void registerListDisable() throws Exception {
        Operator operator = createOperator("wh-" + UUID.randomUUID(), "WH Co");
        createApiClient(operator.getId(), "wh-acme");

        String created = mockMvc.perform(post("/v1/webhooks").with(asPartner("wh-acme", webhookScopes()))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.test/hook\",\"eventTypes\":\"booking.confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andExpect(jsonPath("$.eventTypes").value("booking.confirmed"))
                .andReturn().getResponse().getContentAsString();
        UUID endpointId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(get("/v1/webhooks").with(asPartner("wh-acme", webhookScopes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(endpointId.toString()))
                .andExpect(jsonPath("$[0].signingSecret").doesNotExist());

        mockMvc.perform(delete("/v1/webhooks/{id}", endpointId).with(asPartner("wh-acme", webhookScopes())))
                .andExpect(status().isNoContent());
        assertThat(endpointRepository.findById(endpointId).orElseThrow().getStatus()).isEqualTo("disabled");
    }

    @Test
    void aNonHttpUrlIsRejected() throws Exception {
        Operator operator = createOperator("wh-badurl-" + UUID.randomUUID(), "WH Bad Co");
        createApiClient(operator.getId(), "wh-badurl");

        mockMvc.perform(post("/v1/webhooks").with(asPartner("wh-badurl", webhookScopes()))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.test/hook\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void managingWebhooksNeedsTheScope() throws Exception {
        Operator operator = createOperator("wh-scope-" + UUID.randomUUID(), "WH Scope Co");
        createApiClient(operator.getId(), "wh-scope");

        mockMvc.perform(get("/v1/webhooks").with(asPartner("wh-scope", "trips:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient-scope"));
    }

    @Test
    void aConfirmedBookingIsSignedAndDeliveredToTheEndpoint() throws Exception {
        Operator operator = createOperator("wh-deliver-" + UUID.randomUUID(), "WH Deliver Co");
        createApiClient(operator.getId(), "wh-deliver");
        Trip trip = seedBookableTrip(operator);
        Seat seat = seatRepository.findAllByTripId(trip.getId()).get(0);

        // A local receiver.
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Bustix-Signature"));
            receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-Bustix-Timestamp"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            String created = mockMvc.perform(post("/v1/webhooks").with(asPartner("wh-deliver", webhookScopes()))
                            .header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"http://127.0.0.1:" + port + "/hook\",\"eventTypes\":\"*\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            UUID endpointId = UUID.fromString(objectMapper.readTree(created).get("id").asText());
            String secret = objectMapper.readTree(created).get("signingSecret").asText();

            String bookingBody = objectMapper.writeValueAsString(Map.of(
                    "tripId", trip.getId().toString(),
                    "contactPhone", "+251911234567",
                    "passengers", List.of(Map.of("seatId", seat.getId().toString(), "passengerName", "P"))));
            mockMvc.perform(post("/v1/bookings").with(asPartner("wh-deliver", webhookScopes()))
                            .header("Idempotency-Key", "b-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(bookingBody))
                    .andExpect(status().isOk());

            // The AFTER_COMMIT listener has queued one delivery.
            await().atMost(ofSeconds(3)).until(() ->
                    !deliveryRepository.findTop50ByEndpointIdOrderByCreatedAtDesc(endpointId).isEmpty());

            dispatcher.dispatchDue();

            await().atMost(ofSeconds(3)).until(() -> receivedBody.get() != null);

            assertThat(objectMapper.readTree(receivedBody.get()).get("type").asText()).isEqualTo("booking.confirmed");
            // The signature the receiver got must verify against the body it got.
            long ts = Long.parseLong(receivedTimestamp.get());
            assertThat(receivedSignature.get())
                    .isEqualTo(WebhookSignature.sign(secret, ts, receivedBody.get()));

            var deliveries = deliveryRepository.findTop50ByEndpointIdOrderByCreatedAtDesc(endpointId);
            assertThat(deliveries).isNotEmpty()
                    .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("delivered"));
        } finally {
            server.stop(0);
        }
    }
}
