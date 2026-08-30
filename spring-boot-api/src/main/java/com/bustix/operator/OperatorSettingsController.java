package com.bustix.operator;

import com.bustix.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-operator settings - {@code operator_admin}-managed overrides of the
 * platform-wide business config (VAT rate, reporting buffer, reschedule time
 * gate / fees), the reschedule-notification toggle, and operator contact /
 * ticket-footer info. Tenant-scoped throughout via {@link TenantContext},
 * same as {@code RefundPolicyController} / {@code CargoRateController}.
 *
 * Singleton per operator - no id, no collection, no POST/DELETE. The row is
 * created lazily on the first PATCH; GET works with no row (returns platform
 * defaults). See {@link OperatorSettingsResponse} for the response shape and
 * {@link UpdateOperatorSettingsRequest} for why PATCH is a full replace
 * rather than the app's usual partial update.
 */
@RestController
@RequestMapping("/api/fleet/settings")
public class OperatorSettingsController {

    private final OperatorSettingsService operatorSettingsService;

    public OperatorSettingsController(OperatorSettingsService operatorSettingsService) {
        this.operatorSettingsService = operatorSettingsService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public OperatorSettingsResponse get() {
        return operatorSettingsService.getForTenant(TenantContext.require());
    }

    @PatchMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public OperatorSettingsResponse update(@Valid @RequestBody UpdateOperatorSettingsRequest request) {
        return operatorSettingsService.update(TenantContext.require(), request);
    }
}
