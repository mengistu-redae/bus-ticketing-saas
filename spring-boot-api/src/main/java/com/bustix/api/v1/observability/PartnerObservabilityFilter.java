package com.bustix.api.v1.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Per-partner metering and access logging for the {@code /v1} surface (WS-6).
 * Runs in the security chain (needs the authenticated {@code azp}); each
 * request is:
 * <ul>
 *   <li>counted / timed as {@code bustix.partner.requests} tagged
 *       {@code client}, {@code method}, {@code uri} (the route template, not
 *       the concrete path), {@code status} - scrapeable at
 *       {@code /actuator/prometheus};</li>
 *   <li>logged one line at INFO: {@code [v1] client=… GET /v1/trips 200 12ms}
 *       (the correlation id rides along in the MDC).</li>
 * </ul>
 * Servlet auto-registration is disabled - see {@code SecurityConfig}.
 */
@Component
public class PartnerObservabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.bustix.api.v1.access");

    private final MeterRegistry meterRegistry;

    public PartnerObservabilityFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            String client = currentAzp();
            String route = routeTemplate(request);
            int status = response.getStatus();

            Timer.builder("bustix.partner.requests")
                    .tag("client", client)
                    .tag("method", request.getMethod())
                    .tag("uri", route)
                    .tag("status", String.valueOf(status))
                    .register(meterRegistry)
                    .record(java.time.Duration.ofNanos(elapsedNanos));

            log.info("[v1] client={} {} {} {} {}ms",
                    client, request.getMethod(), route, status, elapsedNanos / 1_000_000);
        }
    }

    private static String currentAzp() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            if (azp != null && !azp.isBlank()) {
                return azp;
            }
        }
        return "unknown";
    }

    /** The matched route template (e.g. {@code /v1/trips/{tripId}}), falling back to the raw path. */
    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
