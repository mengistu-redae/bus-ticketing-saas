package com.bustix.api.v1.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Outbox poller for webhook deliveries - same shape as
 * {@code NotificationWorker}. Signs each delivery (HMAC-SHA256 over
 * {@code <timestamp>.<body>}) and POSTs it; retries a failure with
 * exponential backoff up to {@code maxAttempts}, then marks it {@code failed}.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookProperties properties;
    private final RestClient restClient;

    public WebhookDispatcher(
            WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointRepository endpointRepository,
            WebhookProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${bustix.api.webhooks.poll-interval-ms:10000}")
    public void dispatchDue() {
        List<WebhookDelivery> due = deliveryRepository
                .findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc("pending", Instant.now());
        for (WebhookDelivery delivery : due) {
            deliver(delivery);
        }
    }

    private void deliver(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !"active".equals(endpoint.getStatus())) {
            delivery.setStatus("failed");
            delivery.setLastError("Endpoint no longer active");
            deliveryRepository.save(delivery);
            return;
        }

        long timestamp = Instant.now().getEpochSecond();
        String signature = WebhookSignature.sign(endpoint.getSigningSecret(), timestamp, delivery.getPayload());

        try {
            restClient.post()
                    .uri(endpoint.getUrl())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Bustix-Webhooks/1")
                    .header("X-Bustix-Event", delivery.getEventType())
                    .header("X-Bustix-Delivery", delivery.getId().toString())
                    .header("X-Bustix-Timestamp", String.valueOf(timestamp))
                    .header("X-Bustix-Signature", signature)
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            delivery.setStatus("delivered");
            delivery.setDeliveredAt(Instant.now());
            delivery.setLastError(null);
        } catch (Exception e) {
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setLastError(truncate(e.getMessage()));
            if (delivery.getAttempts() >= properties.getMaxAttempts()) {
                delivery.setStatus("failed");
                log.warn("Webhook delivery {} to {} failed permanently after {} attempts",
                        delivery.getId(), endpoint.getUrl(), delivery.getAttempts());
            } else {
                delivery.setNextAttemptAt(Instant.now().plus(backoff(delivery.getAttempts())));
            }
        }
        deliveryRepository.save(delivery);
    }

    /** 30s, 1m, 2m, 4m, 8m, 16m, 32m, capped at 1h. */
    private static Duration backoff(int attempt) {
        long seconds = Math.min(3600L, 30L * (1L << Math.min(attempt - 1, 10)));
        return Duration.ofSeconds(seconds);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
