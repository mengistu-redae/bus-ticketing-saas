package com.bustix.support;

import com.bustix.fleet.Bus;
import com.bustix.fleet.BusRepository;
import com.bustix.fleet.Route;
import com.bustix.fleet.RouteRepository;
import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.partner.ApiClient;
import com.bustix.partner.ApiClientRepository;
import com.bustix.scheduling.Seat;
import com.bustix.scheduling.SeatRepository;
import com.bustix.scheduling.Trip;
import com.bustix.scheduling.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Base class for controller-level integration tests: a real Spring context,
 * MockMvc driven through the actual filter chain (Spring Security,
 * TenantContextFilter, @PreAuthorize), and real Postgres + Redis via
 * Testcontainers - the same "ddl-auto: validate against real Flyway
 * migrations" and "seat locks really go through Redis" behavior as
 * production, not a stand-in like H2. Images match what docker-compose
 * already uses (postgres:16-alpine, redis:7-alpine) so nothing new needs
 * pulling on a machine that's already run `docker compose up`.
 *
 * Auth is the one thing faked: production authenticates by validating a
 * real Keycloak-issued JWT against KEYCLOAK_ISSUER_URI. These tests instead
 * build a Jwt object directly and inject it via Spring Security Test's
 * jwt() request post-processor - TenantContextFilter, the real
 * JwtAuthenticationConverter bean (autowired below, not re-implemented) and
 * every @PreAuthorize check still run for real; only token issuance is
 * stubbed. See NoNetworkJwtDecoderConfig for why the autoconfigured
 * JwtDecoder (which would otherwise try to reach a real issuer at context
 * startup) is replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(AbstractIntegrationTest.NoNetworkJwtDecoderConfig.class)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // No dedicated Testcontainers Redis module ships upstream - a plain
    // GenericContainer plus @ServiceConnection(name = "redis") is the
    // documented way Spring Boot wires spring.data.redis.host/port from one.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    protected OperatorRepository operatorRepository;

    @Autowired
    protected ApiClientRepository apiClientRepository;

    @Autowired
    protected BusRepository busRepository;

    @Autowired
    protected RouteRepository routeRepository;

    @Autowired
    protected TripRepository tripRepository;

    @Autowired
    protected SeatRepository seatRepository;

    // ---- fixture builders: seed just enough of the tenant-scoped schema
    // for a test's own scenario, letting Flyway/Postgres enforce the same
    // FKs and NOT NULLs production does. ----

    protected Operator createOperator(String keycloakOrgAlias, String name) {
        Operator operator = new Operator();
        operator.setKeycloakOrgId(keycloakOrgAlias);
        operator.setName(name);
        return operatorRepository.save(operator);
    }

    /**
     * A partner API client bound to one operator. The Keycloak side (an
     * actual confidential client) is not created here - integration tests
     * that exercise provisioning mock {@code KeycloakPartnerClient}; this
     * just seeds the local {@code api_clients} row that
     * {@code TenantContextFilter} resolves a partner token's {@code azp}
     * against.
     */
    protected ApiClient createApiClient(UUID tenantId, String keycloakClientId) {
        ApiClient apiClient = new ApiClient();
        apiClient.setKeycloakClientId(keycloakClientId);
        apiClient.setTenantId(tenantId);
        apiClient.setName(keycloakClientId);
        return apiClientRepository.save(apiClient);
    }

    protected Bus createBus(UUID tenantId, String plateNo, int capacity, String seatLayout) {
        Bus bus = new Bus();
        bus.setTenantId(tenantId);
        bus.setPlateNo(plateNo);
        bus.setCapacity(capacity);
        bus.setSeatLayout(seatLayout);
        return busRepository.save(bus);
    }

    protected Route createRoute(UUID tenantId, String origin, String destination) {
        Route route = new Route();
        route.setTenantId(tenantId);
        route.setOrigin(origin);
        route.setDestination(destination);
        return routeRepository.save(route);
    }

    protected Trip createTrip(UUID tenantId, UUID routeId, UUID busId, Instant departureAt, BigDecimal price) {
        Trip trip = new Trip();
        trip.setTenantId(tenantId);
        trip.setRouteId(routeId);
        trip.setBusId(busId);
        trip.setDepartureAt(departureAt);
        trip.setArrivalAt(departureAt.plusSeconds(3600));
        trip.setPrice(price);
        return tripRepository.save(trip);
    }

    protected Seat createSeat(UUID tripId, String seatNo) {
        Seat seat = new Seat();
        seat.setTripId(tripId);
        seat.setSeatNo(seatNo);
        return seatRepository.save(seat);
    }

    // ---- auth builders: hand these straight to MockMvc's .with(...). ----

    protected RequestPostProcessor asCustomer(String subject) {
        return jwtRequest(subject, "customer", null);
    }

    protected RequestPostProcessor asAgent(String subject, String orgAlias) {
        return jwtRequest(subject, "agent", orgAlias);
    }

    protected RequestPostProcessor asOperatorAdmin(String subject, String orgAlias) {
        return jwtRequest(subject, "operator_admin", orgAlias);
    }

    protected RequestPostProcessor asPlatformAdmin(String subject) {
        return jwtRequest(subject, "platform_admin", null);
    }

    /**
     * A third-party partner integration: a Keycloak service-account token
     * from the client-credentials grant. Carries the {@code agent} realm
     * role and an {@code azp} claim (the client id) but NO organization
     * claim - TenantContextFilter resolves its tenant from {@code azp}
     * against {@code api_clients}. Seed the matching row with
     * {@link #createApiClient}.
     */
    protected RequestPostProcessor asPartner(String keycloakClientId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("service-account-" + keycloakClientId)
                .claim("realm_access", Map.of("roles", List.of("agent")))
                .claim("azp", keycloakClientId)
                .claim("scope", "trips:read bookings:read bookings:write")
                .claim("organization", List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return jwt().jwt(jwt).authorities(jwtAuthenticationConverter.convert(jwt).getAuthorities());
    }

    private RequestPostProcessor jwtRequest(String subject, String realmRole, String orgAlias) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(realmRole)))
                .claim("email", subject + "@example.test")
                .claim("name", subject)
                // Present as an empty array rather than omitted when there's
                // no org - that's the real "no organization" shape
                // extractOrgId sees (see TenantContextFilter), not a missing
                // claim.
                .claim("organization", orgAlias != null ? List.of(orgAlias) : List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        // .authorities(jwtAuthenticationConverter) would be the obvious call,
        // but JwtAuthenticationConverter converts to an
        // AbstractAuthenticationToken, not a Collection<GrantedAuthority> -
        // run the real converter and pull the authorities back out instead,
        // so tests exercise the actual ROLE_-prefix mapping rather than a
        // hand-rolled copy of it.
        return jwt().jwt(jwt).authorities(jwtAuthenticationConverter.convert(jwt).getAuthorities());
    }

    /**
     * Replaces the autoconfigured JwtDecoder, which is otherwise built by
     * calling out to KEYCLOAK_ISSUER_URI's OIDC discovery endpoint the
     * moment the context starts (JwtDecoders.fromIssuerLocation is eager,
     * unlike a jwk-set-uri-based decoder). Tests never call decode() at all
     * - the jwt() request post-processor above injects an already-built Jwt
     * straight into the SecurityContext - so this only needs to exist, not
     * do anything real.
     */
    @TestConfiguration
    static class NoNetworkJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                        "Real JWT decoding should never run in tests - authenticate "
                                + "with AbstractIntegrationTest's asCustomer/asAgent/asOperatorAdmin/"
                                + "asPlatformAdmin helpers instead.");
            };
        }
    }
}
