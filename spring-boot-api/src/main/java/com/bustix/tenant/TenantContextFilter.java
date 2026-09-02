package com.bustix.tenant;

import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import com.bustix.partner.ApiClient;
import com.bustix.partner.ApiClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs once per authenticated request, after Spring Security has validated
 * the JWT. Resolves the request's tenant (operator) two ways, in order:
 * <ol>
 *   <li>an {@code organization} claim (staff tokens - operator_admin/agent) →
 *       {@code operators.keycloak_org_id};</li>
 *   <li>failing that, the {@code azp} claim (the OAuth client_id) →
 *       {@code api_clients.keycloak_client_id}: a third-party partner
 *       integration authenticating with the client-credentials grant. Those
 *       tokens carry no organization claim; they act on behalf of one
 *       operator, agent-level. See the Partner API Build Plan / V15.</li>
 * </ol>
 * Customer and platform_admin tokens match neither, so TenantContext stays
 * empty for them - that's expected, not a bug.
 *
 * Also the enforcement point for lockouts: if the resolved operator's status
 * isn't "active", or the partner's {@code api_clients} row is revoked, the
 * token is locked out of the whole API here with a 403, before any
 * controller runs. This is the broad lockout; BookingService.createBooking
 * keeps its own narrower OperatorInactiveException check because that's the
 * only guard for the customer/guest booking path (those tokens carry no org
 * claim, so this filter never sees them).
 *
 * Registered in SecurityConfig with addFilterAfter(...), so it always runs
 * after authentication has populated the SecurityContext.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final OperatorRepository operatorRepository;
    private final ApiClientRepository apiClientRepository;
    private final String orgClaimPath;

    public TenantContextFilter(
            OperatorRepository operatorRepository,
            ApiClientRepository apiClientRepository,
            @Value("${bustix.tenant.org-claim-path}") String orgClaimPath) {
        this.operatorRepository = operatorRepository;
        this.apiClientRepository = apiClientRepository;
        this.orgClaimPath = orgClaimPath;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                Operator operator = extractOrgId(jwt)
                        .flatMap(operatorRepository::findByKeycloakOrgId)
                        .orElse(null);

                if (operator == null) {
                    // No organization claim resolved a tenant. A Keycloak
                    // service-account (client-credentials) token carries none -
                    // if its `azp` is a registered partner API client, it acts
                    // on behalf of that client's operator. A non-partner client
                    // id (the BFF's own included) simply matches no row here.
                    ApiClient apiClient = extractAzp(jwt)
                            .flatMap(apiClientRepository::findByKeycloakClientId)
                            .orElse(null);
                    if (apiClient != null) {
                        if (!"active".equals(apiClient.getStatus())) {
                            writeForbidden(response, "API client access has been revoked");
                            return;
                        }
                        operator = operatorRepository.findById(apiClient.getTenantId()).orElse(null);
                    }
                }

                if (operator != null) {
                    if (!"active".equals(operator.getStatus())) {
                        writeForbidden(response, "Operator account is deactivated");
                        return;
                    }
                    TenantContext.set(operator.getId());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear - threads are pooled and reused across requests.
            TenantContext.clear();
        }
    }

    /**
     * Writes a plain 403 and ends the request. Deliberately not
     * response.sendError(...): that triggers the servlet container's forward
     * to /error, a fresh dispatch back through this filter chain.
     */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    // Package-private (not private) so TenantContextFilterTest can exercise
    // the claim-shape parsing directly without standing up a full filter
    // chain / mocked SecurityContext.
    java.util.Optional<String> extractOrgId(Jwt jwt) {
        Object claim = jwt.getClaims().get(orgClaimPath);
        // Verified against a real token from Keycloak 26.7.1 (oidc-organization-
        // membership-mapper, the built-in mapper backing the "organization"
        // client scope): the claim comes back as a JSON array of org ALIASES,
        // e.g. ["demo-bus-co"], not an id and not an object keyed by org id -
        // that mapper has no config option to emit the org's id instead. This
        // is why operators.keycloak_org_id stores the alias, not Keycloak's
        // internal org UUID (see create-demo-org.sh). A user can belong to at
        // most one org in this app's model, so we take the first element.
        //
        // The String/Map branches below are kept for older or differently-
        // configured Keycloak versions - re-verify against a real token (jwt.io
        // or the Admin Console's "Evaluate" tab) if you're not on 26.7.1.
        if (claim instanceof java.util.List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s && !s.isBlank()) {
                return java.util.Optional.of(s);
            }
        }
        if (claim instanceof String s && !s.isBlank()) {
            return java.util.Optional.of(s);
        }
        if (claim instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
            Object first = map.values().iterator().next();
            if (first instanceof java.util.Map<?, ?> orgObj && orgObj.get("id") != null) {
                return java.util.Optional.of(orgObj.get("id").toString());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The token's {@code azp} (authorized party) claim - the OAuth client_id
     * of whoever obtained the token. Package-private for the same
     * test-visibility reason as {@link #extractOrgId}.
     */
    java.util.Optional<String> extractAzp(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        return (azp == null || azp.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(azp);
    }
}
