package com.bustix.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OperatorSettingsRepository extends JpaRepository<OperatorSettings, UUID> {

    /**
     * The row is keyed by tenant_id, so this is functionally findById - named
     * explicitly for read-site clarity, matching this codebase's
     * every-tenant-scoped-finder-names-its-scope convention.
     */
    Optional<OperatorSettings> findByTenantId(UUID tenantId);
}
