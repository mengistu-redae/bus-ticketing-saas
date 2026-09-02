package com.bustix.api.v1.webhook;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for {@code /v1/webhooks}. */
public final class WebhookDtos {

    private WebhookDtos() {
    }

    /** {@code eventTypes} is a space-delimited list, or omitted/"*" for all. */
    public record CreateWebhookRequest(
            @NotBlank String url,
            String eventTypes) {
    }

    /** The one response that carries {@code signingSecret} - shown once, never again. */
    public record NewWebhookEndpoint(
            UUID id,
            String url,
            String eventTypes,
            String signingSecret) {
    }

    public record WebhookEndpointView(
            UUID id,
            String url,
            String eventTypes,
            String status,
            Instant createdAt) {

        static WebhookEndpointView of(WebhookEndpoint e) {
            return new WebhookEndpointView(e.getId(), e.getUrl(), e.getEventTypes(), e.getStatus(), e.getCreatedAt());
        }
    }

    public record DeliveryView(
            UUID id,
            UUID eventId,
            String eventType,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant deliveredAt) {

        static DeliveryView of(WebhookDelivery d) {
            return new DeliveryView(d.getId(), d.getEventId(), d.getEventType(), d.getStatus(), d.getAttempts(),
                    d.getNextAttemptAt(), d.getLastError(), d.getCreatedAt(), d.getDeliveredAt());
        }
    }

    /** The event types a partner can subscribe to - for the docs. */
    public static final List<String> EVENT_TYPES = List.of(
            "booking.confirmed", "booking.cancelled", "booking.rescheduled",
            "trip.rescheduled", "trip.cancelled", "waybill.status_changed");
}
