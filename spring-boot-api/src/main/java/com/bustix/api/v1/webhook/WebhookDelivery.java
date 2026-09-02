package com.bustix.api.v1.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One queued webhook delivery - written in the same transaction as the
 * domain change (see {@link WebhookEventListener}), dispatched
 * asynchronously by {@link WebhookDispatcher}. Same durable-outbox role as
 * {@code notifications}.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
public class WebhookDelivery {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String payload;

    /** pending, delivered, or failed. */
    @Column(nullable = false)
    private String status = "pending";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
