package com.bustix.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Looks up the local row for a Keycloak subject (jwt.getSubject()). */
    Optional<AppUser> findByKeycloakUserId(String keycloakUserId);
}
