package com.bustix.user;

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
 * Mirrors the subset of Keycloak identity we need to join against locally
 * (bookings.customer_user_id, cancellations.cancelled_by, etc.) - Keycloak
 * remains the source of truth for auth, this table just gets a row created
 * lazily on first login. See CurrentUserService.
 *
 * Doesn't extend BaseTenantEntity: tenantId is nullable here, same as
 * Operator - customers aren't a member of any Organization (see
 * TenantContext), so their row has tenant_id = NULL.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "keycloak_user_id", nullable = false, unique = true)
    private String keycloakUserId;

    /** Null for customers and platform_admins - see TenantContext's javadoc. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** platform_admin, operator_admin, agent, or customer - see the realm roles in realm-export.json. */
    @Column(nullable = false)
    private String role;

    @Column(name = "display_name")
    private String displayName;

    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
