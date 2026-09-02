package com.bustix.api.v1.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a {@link PartnerEvent} into queued {@link WebhookDelivery} rows -
 * one per active endpoint of that operator whose subscription matches the
 * event type. Runs {@code AFTER_COMMIT} of the domain transaction, in its
 * own transaction: a rolled-back booking fires nothing, and a fan-out
 * failure never fails the domain operation (it's logged and the event is
 * dropped, same tradeoff as the notification outbox).
 */
@Component
public class WebhookEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventListener(
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository,
            ObjectMapper objectMapper) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPartnerEvent(PartnerEvent event) {
        List<WebhookEndpoint> endpoints =
                endpointRepository.findAllByTenantIdAndStatus(event.tenantId(), "active");
        if (endpoints.isEmpty()) {
            return;
        }

        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", eventId.toString());
            body.put("type", event.type());
            body.put("createdAt", Instant.now().toString());
            body.put("data", event.data());
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("Could not serialise partner event {} for tenant {}", event.type(), event.tenantId(), e);
            return;
        }

        for (WebhookEndpoint endpoint : endpoints) {
            if (!endpoint.wants(event.type())) {
                continue;
            }
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpointId(endpoint.getId());
            delivery.setEventId(eventId);
            delivery.setEventType(event.type());
            delivery.setPayload(payload);
            deliveryRepository.save(delivery);
        }
    }
}
