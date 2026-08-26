package com.bustix.platform;

/**
 * Partial update - `name` and `tin` are editable. `keycloak_org_id` is
 * deliberately not: it's the alias TenantContextFilter matches against a
 * staff token's organization claim, so changing it post-creation would
 * silently break tenant resolution for every existing staff login at that
 * operator. `tin` carries no such risk - it doesn't feed tenant
 * resolution, just what's printed on a passenger ticket.
 */
public record UpdateOperatorRequest(
    String name,
    String tin
) {
}
