package com.bustix.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    /** Maps a Keycloak Organization id (from the JWT) to our internal tenant id. */
    Optional<Operator> findByKeycloakOrgId(String keycloakOrgId);

    /** platform_admin dashboard: active vs inactive operator split. */
    long countByStatus(String status);

    java.util.List<Operator> findTop5ByOrderByCreatedAtDesc();
}
