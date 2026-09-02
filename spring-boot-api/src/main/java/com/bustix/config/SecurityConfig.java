package com.bustix.config;

import com.bustix.tenant.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
// Without this, @PreAuthorize on BookingController/CancellationController/etc.
// is silently never evaluated - Spring Security 6 does not enable
// method-level security by default just because @PreAuthorize is present on
// a method. Confirmed missing and fixed 2026-08-23: every @PreAuthorize
// check in this codebase was inert until this annotation was added.
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            TenantContextFilter tenantContextFilter,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless bearer-token API, called only by the BFF
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                // Spring's DefaultHandlerExceptionResolver (e.g. for a
                // @Valid bean-validation failure that no controller-local
                // @ExceptionHandler catches) writes an error status via
                // response.sendError(...), which triggers the servlet
                // container's internal forward to /error - a NEW request
                // dispatch that re-enters this filter chain. Without this
                // line that forward fell into anyRequest().denyAll() and
                // got rewritten into a misleading 403 "insufficient_scope"
                // response, masking the real 400 (or whatever status the
                // original exception resolved to) - found live 2026-08-24
                // testing the new passenger-phone @Pattern validation.
                // permitAll() here doesn't weaken anything: it only governs
                // whether this internal forward is allowed to render the
                // already-decided error status, not authentication/
                // authorization of the original request that failed.
                .requestMatchers("/error").permitAll()
                // Public, unauthenticated cargo tracking (waybill number +
                // phone as a two-factor lookup, no session) - must be listed
                // before the blanket /api/** authenticated() rule below,
                // since authorizeHttpRequests matches in order. See
                // CargoWaybillController.track and decision 9 in
                // my-notes/cargo_logistics_scope_v1.md for why this one path
                // has no auth requirement at all.
                .requestMatchers("/api/cargo/track/**").permitAll()
                // Public guest-booking surface, added alongside the above -
                // a visitor with no account needs to search/view trips,
                // book, and look that booking back up later, all with no
                // JWT. See TripController.search/locations/seats/
                // getTripDetails and BookingController.createGuestBooking/
                // trackGuestBooking for why each of these methods carries
                // no @PreAuthorize.
                .requestMatchers(HttpMethod.GET, "/api/trips/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/trips/locations").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/trips/*/seats").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/trips/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/bookings/guest").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/bookings/guest/track/*").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            // TenantContextFilter needs SecurityContext populated first, so
            // it runs immediately after the bearer-token auth filter.
            .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Maps a Keycloak token to Spring Security authorities:
     * <ul>
     *   <li>{@code realm_access.roles} → {@code ROLE_*}, so
     *       {@code @PreAuthorize("hasRole('AGENT')")} etc. work;</li>
     *   <li>the OAuth {@code scope} claim → {@code SCOPE_*}, so the partner
     *       {@code /v1} surface can gate on {@code hasAuthority('SCOPE_bookings:write')}.
     *       Human logins through the BFF request only {@code openid profile
     *       email}, so this adds nothing for them; client-credentials partner
     *       tokens carry the granted business scopes.</li>
     * </ul>
     *
     * NOTE: extractAuthorities is deliberately NOT its own
     * Converter<Jwt, Collection<GrantedAuthority>> @Bean, even though that
     * would be convenient for integration tests to reuse directly - Spring
     * Boot's WebMvcAutoConfiguration auto-registers every Converter-typed
     * bean into the global MVC ConversionService, and since extractAuthorities
     * is a lambda/method reference, Spring can't reflectively resolve its
     * generic <S,T> types, which throws IllegalArgumentException
     * ("Unable to determine source type <S> and target type <T>") and fails
     * the whole application context at startup. Found the hard way on
     * 2026-08-23: broke `mvn spring-boot:run` outright, not just tests.
     * AbstractIntegrationTest instead gets the same mapping by calling
     * `jwtAuthenticationConverter.convert(jwt).getAuthorities()` on the bean
     * below, which is exactly what production authentication does too.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") != null) {
            for (String role : (List<String>) realmAccess.get("roles")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
            }
        }

        // OAuth scopes are a single space-delimited string per RFC 6749.
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            for (String s : scope.split(" ")) {
                if (!s.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
                }
            }
        }

        return authorities;
    }
}
