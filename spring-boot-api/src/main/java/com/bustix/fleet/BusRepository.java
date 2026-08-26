package com.bustix.fleet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusRepository extends JpaRepository<Bus, UUID> {

    // Explicit tenant_id in every finder used by staff endpoints - never rely
    // on an implicit filter. See TenantContext.java for the reasoning.
    List<Bus> findAllByTenantId(UUID tenantId);

    Optional<Bus> findByIdAndTenantId(UUID id, UUID tenantId);
}
