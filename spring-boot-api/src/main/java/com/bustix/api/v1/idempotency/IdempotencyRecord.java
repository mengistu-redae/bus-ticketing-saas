package com.bustix.api.v1.idempotency;

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
 * One recorded {@code /v1} write, keyed by (api client, idempotency key).
 * See V16 and {@link IdempotencyFilter}.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "api_client_id", nullable = false)
    private String apiClientId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String method;

    @Column(nullable = false)
    private String path;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    /** Null until the first request's handler has completed. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
