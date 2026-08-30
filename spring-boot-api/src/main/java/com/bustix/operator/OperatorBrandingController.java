package com.bustix.operator;

import com.bustix.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-operator branding - logo URL, colours, customer-facing name. Stored
 * on the same {@code operator_settings} row as the business-value overrides
 * but with its <b>own</b> endpoint: {@code PATCH /api/fleet/settings} is a
 * full replace of the override set, so sharing that request would have the
 * General settings tab wipe branding and vice-versa. This controller writes
 * a disjoint column set.
 *
 * {@code GET} is open to {@code AGENT} too (the staff SPA themes itself
 * from it); {@code PATCH} is {@code OPERATOR_ADMIN} only.
 */
@RestController
@RequestMapping("/api/operator/branding")
public class OperatorBrandingController {

    private final OperatorSettingsService operatorSettingsService;
    private final OperatorRepository operatorRepository;

    public OperatorBrandingController(OperatorSettingsService operatorSettingsService,
                                      OperatorRepository operatorRepository) {
        this.operatorSettingsService = operatorSettingsService;
        this.operatorRepository = operatorRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR_ADMIN', 'AGENT')")
    public OperatorBrandingView get() {
        UUID tenantId = TenantContext.require();
        return operatorSettingsService.brandingFor(tenantId, operatorName(tenantId));
    }

    @PatchMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public OperatorBrandingView update(@Valid @RequestBody UpdateOperatorBrandingRequest request) {
        UUID tenantId = TenantContext.require();
        return operatorSettingsService.updateBranding(tenantId, operatorName(tenantId), request);
    }

    private String operatorName(UUID tenantId) {
        return operatorRepository.findById(tenantId).map(Operator::getName).orElse(null);
    }
}
