package com.bustix.partner;

/**
 * Partial update - only non-null/non-blank fields are applied. Deliberately
 * narrow (like {@code UpdateOperatorRequest}): {@code keycloakClientId} and
 * {@code tenantId} are fixed at creation, and changing the granted
 * {@code scopes} means re-syncing Keycloak, so that is delete-and-recreate.
 */
public record UpdatePartnerRequest(
    String name,
    String rateTier
) {
}
