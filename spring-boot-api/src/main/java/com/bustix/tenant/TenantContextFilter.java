package com.bustix.tenant;

import com.bustix.operator.OperatorRepository;
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
 * the JWT. If the token carries an organization/org claim (staff tokens),
 * resolves it to our internal tenant id and puts it in TenantContext for the
 * rest of the request. Customer and platform_admin tokens have no such
 * claim, so TenantContext stays empty for them - that's expected, not a bug.
 *
 * Also the enforcement point for operator deactivation: if the resolved
 * operator's status isn't "active", the staff token is locked out of the
 * whole API here with a 403, before any controller runs. This is the broad
 * lockout; BookingService.createBooking keeps its own narrower
 * OperatorInactiveException check because that's the only guard for the
 * customer/guest booking path (those tokens carry no org claim, so this
 * filter never sees them).
 *
 * Registered in SecurityConfig with addFilterAfter(...), so it always runs
 * after authentication has populated the SecurityContext.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final OperatorRepository operatorRepository;
    private final String orgClaimPath;

    public TenantContextFilter(
            OperatorRepository operatorRepository,
            @Value("${bustix.tenant.org-claim-path}") String orgClaimPath) {
        this.operatorRepository = operatorRepository;
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
                var operator = extractOrgId(jwt)
                        .flatMap(operatorRepository::findByKeycloakOrgId)
                        .orElse(null);
                if (operator != null) {
                    if (!"active".equals(operator.getStatus())) {
                        // Hard lockout - a deactivated operator's staff lose all
                        // API access, not just booking creation. Write the body
                        // directly rather than response.sendError(...): sendError
                        // triggers the servlet container's forward to /error, a
                        // fresh dispatch back through this chain.
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("text/plain;charset=UTF-8");
                        response.getWriter().write("Operator account is deactivated");
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
}
