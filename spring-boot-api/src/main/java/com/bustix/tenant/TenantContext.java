package com.bustix.tenant;

import java.util.UUID;

/**
 * Holds the current request's tenant (operator) id, if any.
 *
 * Deliberately NOT wired into a blanket Hibernate multi-tenant filter - see
 * the README section "How tenant filtering works". Staff-scoped repository
 * methods should take the tenant id explicitly as a parameter
 * (e.g. findByTenantIdAndId(...)) rather than reading this implicitly deep
 * inside a query, so it's always obvious from the method signature whether
 * an endpoint is tenant-scoped or cross-tenant.
 *
 * Empty (null) for:
 *  - customer tokens (customers are not members of any Organization)
 *  - platform_admin tokens acting across tenants
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT_TENANT.get();
    }

    public static UUID require() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "No tenant on this request - this endpoint requires a staff token " +
                "(operator_admin/agent) with an organization membership, not a customer or platform_admin token."
            );
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
