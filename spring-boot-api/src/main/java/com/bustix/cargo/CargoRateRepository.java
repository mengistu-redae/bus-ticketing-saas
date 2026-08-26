package com.bustix.cargo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CargoRateRepository extends JpaRepository<CargoRate, UUID> {

    /** The route-specific override, if the operator has configured one. */
    Optional<CargoRate> findByTenantIdAndRouteId(UUID tenantId, UUID routeId);

    /** The operator-wide default (route_id IS NULL). */
    Optional<CargoRate> findByTenantIdAndRouteIdIsNull(UUID tenantId);

    // Backs CargoRateController's CRUD surface.
    List<CargoRate> findAllByTenantId(UUID tenantId);

    Optional<CargoRate> findByIdAndTenantId(UUID id, UUID tenantId);
}
