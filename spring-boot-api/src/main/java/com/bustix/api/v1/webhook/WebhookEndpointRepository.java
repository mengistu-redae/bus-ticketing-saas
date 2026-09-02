package com.bustix.api.v1.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /** Delivery fan-out: every active endpoint for this operator. */
    List<WebhookEndpoint> findAllByTenantIdAndStatus(UUID tenantId, String status);

    /** A partner managing its own endpoints. */
    List<WebhookEndpoint> findAllByApiClientIdOrderByCreatedAtDesc(String apiClientId);

    Optional<WebhookEndpoint> findByIdAndApiClientId(UUID id, String apiClientId);
}
