package com.bustix.tenant;

import com.bustix.operator.OperatorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Locks in the real Keycloak 26.7.1 claim shape found by decoding an actual
 * token during manual testing: "organization" comes back as a JSON array of
 * org ALIASES (e.g. ["demo-bus-co"]), not a bare string and not an object
 * keyed by org id. Before this fix, extractOrgId only handled those latter
 * two shapes and silently returned empty for every real staff token.
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter =
        new TenantContextFilter(mock(OperatorRepository.class), "organization");

    @Test
    void extractsTheFirstAliasFromTheRealKeycloak26ListShape() {
        Jwt jwt = jwtWithClaim("organization", List.of("demo-bus-co"));

        assertThat(filter.extractOrgId(jwt)).contains("demo-bus-co");
    }

    @Test
    void ignoresAdditionalOrganizationsBeyondTheFirst() {
        // This app's tenancy model assumes a staff user belongs to exactly
        // one org - see the comment on extractOrgId.
        Jwt jwt = jwtWithClaim("organization", List.of("demo-bus-co", "some-other-org"));

        assertThat(filter.extractOrgId(jwt)).contains("demo-bus-co");
    }

    @Test
    void emptyListYieldsEmptyOptional() {
        Jwt jwt = jwtWithClaim("organization", List.of());

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    @Test
    void handlesAPlainStringClaimForOlderOrDifferentlyConfiguredKeycloak() {
        Jwt jwt = jwtWithClaim("organization", "demo-bus-co");

        assertThat(filter.extractOrgId(jwt)).contains("demo-bus-co");
    }

    @Test
    void handlesTheIdKeyedMapShapeSomeKeycloakVersionsUse() {
        Jwt jwt = jwtWithClaim("organization", Map.of("demo-bus-co", Map.of("id", "org-uuid-123")));

        assertThat(filter.extractOrgId(jwt)).contains("org-uuid-123");
    }

    @Test
    void missingClaimYieldsEmptyOptionalForCustomerAndPlatformAdminTokens() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", "some-user")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    @Test
    void nonStringListElementYieldsEmptyOptionalRatherThanThrowing() {
        Jwt jwt = jwtWithClaim("organization", List.of(42));

        assertThat(filter.extractOrgId(jwt)).isEmpty();
    }

    private Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("sub", "some-user")
            .claim(name, value)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    }
}
