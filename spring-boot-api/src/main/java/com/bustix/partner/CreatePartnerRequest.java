package com.bustix.partner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Onboards a third-party integration for one operator. {@code operatorId} is
 * the internal {@code operators.id}, not a Keycloak alias - the platform
 * admin picks it from the operator list. The Keycloak client id is generated,
 * not supplied.
 */
public record CreatePartnerRequest(
    @NotBlank String name,
    @NotNull UUID operatorId,
    /** OAuth scopes to grant, e.g. ["trips:read", "bookings:write"]. Recorded now, enforced at the /v1 surface (WS-2). */
    List<String> scopes,
    /** Rate-limit tier (WS-4); defaults to "default" when null/blank. */
    String rateTier
) {
}
