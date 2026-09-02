package com.bustix.partner;

import java.util.UUID;

/**
 * The response to creating a partner - the one place {@code clientSecret} is
 * ever returned. It is not stored by Bustix; if it's lost the partner must be
 * deleted and recreated. A purpose-built read shape (like
 * {@code WaybillWithItems}) rather than the {@code ApiClient} entity, since
 * the secret is not a column on it.
 */
public record NewPartnerCredential(
    UUID id,
    String keycloakClientId,
    String clientSecret,
    UUID operatorId,
    String name,
    String scopes
) {
}
