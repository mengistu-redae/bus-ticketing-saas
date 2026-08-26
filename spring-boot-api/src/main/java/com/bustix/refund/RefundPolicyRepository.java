package com.bustix.refund;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundPolicyRepository extends JpaRepository<RefundPolicy, UUID> {

    /** The route-specific override, if the operator has configured one. */
    Optional<RefundPolicy> findByTenantIdAndRouteId(UUID tenantId, UUID routeId);

    /** The operator-wide default (route_id IS NULL). */
    Optional<RefundPolicy> findByTenantIdAndRouteIdIsNull(UUID tenantId);

    // Backs RefundPolicyController's CRUD surface.
    List<RefundPolicy> findAllByTenantId(UUID tenantId);

    Optional<RefundPolicy> findByIdAndTenantId(UUID id, UUID tenantId);
}
