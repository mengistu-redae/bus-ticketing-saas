package com.bustix.api.v1.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /** The dispatcher's poll: pending rows whose backoff has elapsed, oldest first. */
    List<WebhookDelivery> findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(String status, Instant cutoff);

    List<WebhookDelivery> findTop50ByEndpointIdOrderByCreatedAtDesc(UUID endpointId);
}
