package com.bustix.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundCalculatorTest {

    private static final String TIERS_JSON =
        "[{\"cutoff_hours\":24,\"refund_percent\":100},"
        + "{\"cutoff_hours\":2,\"refund_percent\":50},"
        + "{\"cutoff_hours\":0,\"refund_percent\":0}]";

    // Deliberately NOT authored highest-cutoff-first, to prove
    // RefundCalculator sorts defensively rather than trusting storage order
    // - see the comment in RefundCalculator.calculate.
    private static final String TIERS_JSON_OUT_OF_ORDER =
        "[{\"cutoff_hours\":0,\"refund_percent\":0},"
        + "{\"cutoff_hours\":24,\"refund_percent\":100},"
        + "{\"cutoff_hours\":2,\"refund_percent\":50}]";

    @Mock
    private RefundPolicyRepository refundPolicyRepository;

    private RefundCalculator refundCalculator;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        refundCalculator = new RefundCalculator(refundPolicyRepository, new ObjectMapper());
    }

    @Test
    void returnsZeroWhenOperatorHasNoPolicyConfigured() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId)).thenReturn(Optional.empty());
        when(refundPolicyRepository.findByTenantIdAndRouteIdIsNull(tenantId)).thenReturn(Optional.empty());

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("0");
    }

    @Test
    void appliesFullRefundTierWhenWellBeforeCutoff() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON)));

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("500.00");
    }

    @Test
    void appliesMiddleTierBetweenCutoffs() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON)));

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(10, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("250.00");
    }

    @Test
    void appliesZeroTierPastFinalCutoff() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON)));

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("0.00");
    }

    @Test
    void sortsTiersHighestCutoffFirstRegardlessOfStorageOrder() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON_OUT_OF_ORDER)));

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("500.00");
    }

    @Test
    void routeSpecificPolicyOverridesOperatorWideDefaultWithoutConsultingIt() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON)));

        refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS));

        verify(refundPolicyRepository, never()).findByTenantIdAndRouteIdIsNull(any());
    }

    @Test
    void fallsBackToOperatorWideDefaultWhenNoRouteSpecificPolicyExists() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId)).thenReturn(Optional.empty());
        when(refundPolicyRepository.findByTenantIdAndRouteIdIsNull(tenantId))
            .thenReturn(Optional.of(policyWith(TIERS_JSON)));

        BigDecimal refund = refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS));

        assertThat(refund).isEqualByComparingTo("500.00");
    }

    @Test
    void malformedRulesJsonThrowsRatherThanSilentlyReturningZero() {
        when(refundPolicyRepository.findByTenantIdAndRouteId(tenantId, routeId))
            .thenReturn(Optional.of(policyWith("not valid json")));

        assertThatThrownBy(() -> refundCalculator.calculate(
            tenantId, routeId, new BigDecimal("500.00"), Instant.now().plus(48, ChronoUnit.HOURS)))
            .isInstanceOf(IllegalStateException.class);
    }

    private RefundPolicy policyWith(String rulesJson) {
        RefundPolicy policy = new RefundPolicy();
        policy.setTenantId(tenantId);
        policy.setRules(rulesJson);
        return policy;
    }
}
