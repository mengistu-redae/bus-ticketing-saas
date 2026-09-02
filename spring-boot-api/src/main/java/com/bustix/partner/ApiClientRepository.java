package com.bustix.partner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiClientRepository extends JpaRepository<ApiClient, UUID> {

    /**
     * Maps a token's {@code azp} claim (the OAuth client_id) to a partner
     * credential - {@code TenantContextFilter}'s tenant-resolution fallback
     * for client-credentials tokens, which carry no organization claim.
     * Returns empty for every non-partner client id (the BFF's own client
     * included - no row is ever created for it).
     */
    Optional<ApiClient> findByKeycloakClientId(String keycloakClientId);

    List<ApiClient> findAllByOrderByCreatedAtDesc();
}
