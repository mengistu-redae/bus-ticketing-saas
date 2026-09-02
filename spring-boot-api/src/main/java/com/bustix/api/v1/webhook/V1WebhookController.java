package com.bustix.api.v1.webhook;

import com.bustix.api.v1.webhook.WebhookDtos.CreateWebhookRequest;
import com.bustix.api.v1.webhook.WebhookDtos.DeliveryView;
import com.bustix.api.v1.webhook.WebhookDtos.NewWebhookEndpoint;
import com.bustix.api.v1.webhook.WebhookDtos.WebhookEndpointView;
import com.bustix.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A partner registers and manages its own webhook endpoints. Delivery
 * fan-out is by operator - see {@link WebhookEventListener} - so a partner
 * receives events for its operator's activity, whoever triggered it.
 *
 * All endpoints require the {@code webhooks:manage} scope.
 */
@RestController
@RequestMapping("/v1/webhooks")
@PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
@Tag(name = "Webhooks", description = "Register callback URLs for booking / trip / waybill events.")
public class V1WebhookController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookUrlValidator urlValidator;

    public V1WebhookController(
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookUrlValidator urlValidator) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.urlValidator = urlValidator;
    }

    @PostMapping
    @Operation(summary = "Register a webhook endpoint",
            description = "Returns the signing secret once. Verify X-Bustix-Signature = "
                    + "\"sha256=\" + HMAC_SHA256(secret, X-Bustix-Timestamp + \".\" + rawBody).")
    public NewWebhookEndpoint create(@Valid @RequestBody CreateWebhookRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        urlValidator.validate(request.url());

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(TenantContext.require());
        endpoint.setApiClientId(azp(jwt));
        endpoint.setUrl(request.url());
        endpoint.setSigningSecret(newSecret());
        endpoint.setEventTypes(normaliseEventTypes(request.eventTypes()));
        endpointRepository.save(endpoint);

        return new NewWebhookEndpoint(
                endpoint.getId(), endpoint.getUrl(), endpoint.getEventTypes(), endpoint.getSigningSecret());
    }

    @GetMapping
    @Operation(summary = "List this partner's webhook endpoints")
    public List<WebhookEndpointView> list(@AuthenticationPrincipal Jwt jwt) {
        return endpointRepository.findAllByApiClientIdOrderByCreatedAtDesc(azp(jwt)).stream()
                .map(WebhookEndpointView::of)
                .toList();
    }

    @GetMapping("/{endpointId}/deliveries")
    @Operation(summary = "Recent delivery attempts for one endpoint")
    public List<DeliveryView> deliveries(@PathVariable UUID endpointId, @AuthenticationPrincipal Jwt jwt) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndApiClientId(endpointId, azp(jwt))
                .orElseThrow(() -> new NoSuchElementException("Webhook endpoint not found: " + endpointId));
        return deliveryRepository.findTop50ByEndpointIdOrderByCreatedAtDesc(endpoint.getId()).stream()
                .map(DeliveryView::of)
                .toList();
    }

    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disable a webhook endpoint",
            description = "Sets it to disabled (past deliveries are kept for audit); no more events are queued to it.")
    public void disable(@PathVariable UUID endpointId, @AuthenticationPrincipal Jwt jwt) {
        WebhookEndpoint endpoint = endpointRepository.findByIdAndApiClientId(endpointId, azp(jwt))
                .orElseThrow(() -> new NoSuchElementException("Webhook endpoint not found: " + endpointId));
        endpoint.setStatus("disabled");
        endpointRepository.save(endpoint);
    }

    private static String azp(Jwt jwt) {
        return jwt.getClaimAsString("azp");
    }

    private static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private static String normaliseEventTypes(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equals("*")) {
            return "*";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }
}
