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

/** A partner-registered callback URL. See V17 and {@link WebhookDispatcher}. */
@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
public class WebhookEndpoint {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The partner (token azp) that owns this endpoint. */
    @Column(name = "api_client_id", nullable = false)
    private String apiClientId;

    @Column(nullable = false)
    private String url;

    @Column(name = "signing_secret", nullable = false)
    private String signingSecret;

    /** Space-delimited event types, or {@code *} for all. */
    @Column(name = "event_types", nullable = false)
    private String eventTypes = "*";

    /** active or disabled. */
    @Column(nullable = false)
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Whether this endpoint wants the given event type. */
    public boolean wants(String eventType) {
        if ("*".equals(eventTypes)) {
            return true;
        }
        for (String t : eventTypes.split("\\s+")) {
            if (t.equals(eventType) || "*".equals(t)) {
                return true;
            }
        }
        return false;
    }
}
