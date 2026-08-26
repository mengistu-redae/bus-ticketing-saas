package com.bustix.refund;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Applies an operator's refund_policies tiers to a cancellation. A
 * route-specific policy (route_id set) overrides the operator-wide default
 * (route_id NULL) - see the comment on refund_policies in V1__init.sql.
 */
@Service
public class RefundCalculator {

    private final RefundPolicyRepository refundPolicyRepository;
    private final ObjectMapper objectMapper;

    public RefundCalculator(RefundPolicyRepository refundPolicyRepository, ObjectMapper objectMapper) {
        this.refundPolicyRepository = refundPolicyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns zero if the operator hasn't configured a policy yet, rather
     * than failing the cancellation outright - a missing policy is a
     * configuration gap, not grounds to block a customer's cancellation.
     */
    public BigDecimal calculate(UUID tenantId, UUID routeId, BigDecimal totalAmount, Instant departureAt) {
        RefundPolicy policy = refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId)
            .or(() -> refundPolicyRepository.findByTenantIdAndRouteIdIsNull(tenantId))
            .orElse(null);
        if (policy == null) {
            return BigDecimal.ZERO;
        }

        long hoursUntilDeparture = Duration.between(Instant.now(), departureAt).toHours();
        int refundPercent = parseTiers(policy.getRules()).stream()
            // Tiers are meant to be authored highest-cutoff-first (see the
            // JSON example in V1__init.sql), but sort defensively so storage
            // order can never change which tier applies.
            .sorted(Comparator.comparingInt(RefundTier::cutoffHours).reversed())
            .filter(tier -> hoursUntilDeparture >= tier.cutoffHours())
            .findFirst()
            .map(RefundTier::refundPercent)
            .orElse(0);

        return totalAmount
            .multiply(BigDecimal.valueOf(refundPercent))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private List<RefundTier> parseTiers(String rulesJson) {
        try {
            return objectMapper.readValue(rulesJson, new TypeReference<List<RefundTier>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed refund_policies.rules JSON", e);
        }
    }
}
