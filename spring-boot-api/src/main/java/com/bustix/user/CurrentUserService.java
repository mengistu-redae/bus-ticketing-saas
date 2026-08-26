package com.bustix.user;

import com.bustix.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the JWT in front of every request to our internal app_user id,
 * provisioning the row on first login. Both BookingController and
 * CancellationController need this, hence its own bean rather than a
 * private helper duplicated in each controller.
 *
 * The write is here (not inline in the caller) for the same reason
 * BookingWriter is split out of BookingService: it's @Transactional, and
 * calling an @Transactional method on `this` from inside the same class
 * silently skips the proxy and runs without a transaction.
 */
@Service
public class CurrentUserService {

    /** Highest-privilege role wins if a token somehow carries more than one. */
    private static final List<String> ROLE_PRECEDENCE =
        List.of("platform_admin", "operator_admin", "agent", "customer");

    private final AppUserRepository appUserRepository;

    public CurrentUserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public UUID resolveInternalUserId(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return appUserRepository.findByKeycloakUserId(keycloakUserId)
            .map(AppUser::getId)
            .orElseGet(() -> provision(jwt, keycloakUserId));
    }

    // Public, not private/protected: Spring's proxy-based @Transactional
    // only takes effect on public methods - see the class javadoc above.
    @Transactional
    public UUID provision(Jwt jwt, String keycloakUserId) {
        AppUser user = new AppUser();
        user.setKeycloakUserId(keycloakUserId);
        // Set by TenantContextFilter from the org claim for staff tokens;
        // stays null for customer/platform_admin tokens, same as Operator.
        user.setTenantId(TenantContext.get());
        user.setRole(primaryRole(jwt));
        user.setEmail(jwt.getClaimAsString("email"));
        user.setDisplayName(jwt.getClaimAsString("name"));

        try {
            return appUserRepository.save(user).getId();
        } catch (DataIntegrityViolationException e) {
            // Two concurrent first requests from the same brand-new user
            // both missed the findByKeycloakUserId check above and raced to
            // insert - keycloak_user_id's unique constraint lets exactly one
            // win. Fall back to whichever row actually landed instead of
            // failing the request.
            return appUserRepository.findByKeycloakUserId(keycloakUserId)
                .map(AppUser::getId)
                .orElseThrow(() -> e);
        }
    }

    private String primaryRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        @SuppressWarnings("unchecked")
        List<String> roles = realmAccess == null ? List.of() : (List<String>) realmAccess.getOrDefault("roles", List.of());
        return ROLE_PRECEDENCE.stream()
            .filter(roles::contains)
            .findFirst()
            .orElse("customer");
    }
}
