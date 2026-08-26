package com.bustix.platform;

import jakarta.validation.constraints.NotBlank;

public record CreateOperatorRequest(
    @NotBlank String name,
    /** Also becomes operators.keycloak_org_id - see TenantContextFilter.extractOrgId. */
    @NotBlank String orgAlias,
    /** Keycloak Organizations require at least one domain. */
    @NotBlank String domain,
    /** Optional tax identification number, shown on this operator's passenger tickets. */
    String tin
) {
}
