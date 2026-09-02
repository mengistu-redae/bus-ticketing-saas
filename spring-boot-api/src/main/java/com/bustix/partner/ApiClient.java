package com.bustix.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A third-party integration credential: one confidential Keycloak client,
 * authenticating with the client-credentials grant, acting on behalf of
 * exactly one operator (agent-level capability). See V15 and the Partner API
 * Build Plan.
 *
 * Like {@code AppUser}, this deliberately declares its own id/tenantId rather
 * than extending {@code BaseTenantEntity}: {@code tenant_id} here is the
 * operator this partner is bound to, resolved from the token's {@code azp}
 * claim by {@code TenantContextFilter}, not from an organization claim.
 */
@Entity
@Table(name = "api_clients")
@Getter
@Setter
public class ApiClient {

    @Id
    @GeneratedValue
    private UUID id;

    /** The OAuth client_id - exactly what a token's {@code azp} claim carries. */
    @Column(name = "keycloak_client_id", nullable = false, unique = true)
    private String keycloakClientId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    /** active, or revoked (a hard API lockout in TenantContextFilter). */
    @Column(nullable = false)
    private String status = "active";

    /** Space-delimited OAuth scopes granted; enforced at the /v1 surface, not here. */
    @Column(nullable = false)
    private String scopes = "";

    /** Consumed by per-partner rate limiting (WS-4); unused until then. */
    @Column(name = "rate_tier", nullable = false)
    private String rateTier = "default";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
