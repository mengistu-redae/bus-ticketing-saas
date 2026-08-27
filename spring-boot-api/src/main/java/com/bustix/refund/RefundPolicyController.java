package com.bustix.refund;

import com.bustix.fleet.RouteRepository;
import com.bustix.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Operator-configured refund tiers - previously only manageable by hand-
 * running SQL (see the README's "seed a refund policy" step). Tenant-scoped
 * throughout; RefundCalculator (unaffected by this controller - it just
 * reads whatever rows exist) is what actually applies these at cancellation
 * time, see CLAUDE.md's "Refund policy" section for the tier-matching
 * rules.
 *
 * Returns RefundPolicy entities directly, same convention as every other
 * fleet-management controller in this codebase - `rules` therefore comes
 * back as an escaped JSON string (its storage representation via
 * @JdbcTypeCode(SqlTypes.JSON)), not a nested array, since this app has no
 * DTO-shaping layer anywhere else either. The request side is structured
 * (see CreateRefundPolicyRequest) even though the response isn't, so at
 * least writes are validated.
 */
@RestController
@RequestMapping("/api/fleet/refund-policies")
public class RefundPolicyController {

    private final RefundPolicyRepository refundPolicyRepository;
    private final RouteRepository routeRepository;
    private final ObjectMapper objectMapper;

    public RefundPolicyController(
            RefundPolicyRepository refundPolicyRepository,
            RouteRepository routeRepository,
            ObjectMapper objectMapper) {
        this.refundPolicyRepository = refundPolicyRepository;
        this.routeRepository = routeRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public List<RefundPolicy> list() {
        return refundPolicyRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public RefundPolicy get(@PathVariable UUID policyId) {
        return findOwnedPolicy(policyId);
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public RefundPolicy create(@Valid @RequestBody CreateRefundPolicyRequest request) {
        UUID tenantId = TenantContext.require();
        RefundPolicy policy = new RefundPolicy();
        policy.setTenantId(tenantId);
        policy.setRouteId(requireOwnedRouteOrNull(request.routeId(), tenantId));
        policy.setRules(writeTiers(request.tiers()));
        return refundPolicyRepository.save(policy);
    }

    @PatchMapping("/{policyId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public RefundPolicy update(@PathVariable UUID policyId, @Valid @RequestBody UpdateRefundPolicyRequest request) {
        RefundPolicy policy = findOwnedPolicy(policyId);
        if (request.tiers() != null && !request.tiers().isEmpty()) {
            policy.setRules(writeTiers(request.tiers()));
        }
        return refundPolicyRepository.save(policy);
    }

    /**
     * Real delete, not a soft-deactivate - unlike bookings/payments this is
     * operator configuration, not a financial/audit record, and
     * RefundCalculator already has a well-defined, safe fallback for "no
     * policy configured" (refund = 0%, not an error), so removing the row
     * doesn't leave anything in an inconsistent state.
     */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public void delete(@PathVariable UUID policyId) {
        refundPolicyRepository.delete(findOwnedPolicy(policyId));
    }

    private RefundPolicy findOwnedPolicy(UUID policyId) {
        return refundPolicyRepository.findByIdAndTenantId(policyId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Refund policy not found: " + policyId));
    }

    /**
     * A route-specific policy's routeId must belong to the caller's own
     * operator - same cross-reference-validation principle TripCreationService
     * uses for a trip's route/bus. Null (the operator-wide default) is fine.
     */
    private UUID requireOwnedRouteOrNull(UUID routeId, UUID tenantId) {
        if (routeId == null) {
            return null;
        }
        routeRepository.findByIdAndTenantId(routeId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Route not found: " + routeId));
        return routeId;
    }

    private String writeTiers(List<RefundTier> tiers) {
        try {
            return objectMapper.writeValueAsString(tiers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize refund tiers", e);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
