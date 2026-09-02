package com.bustix.api.v1.ratelimit;

import com.bustix.api.v1.ProblemJson;
import com.bustix.partner.ApiClient;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-partner token-bucket rate limiting for the {@code /v1} surface (WS-4).
 * Keyed by the token's {@code azp} (OAuth client_id); the budget comes from
 * the partner's {@code api_clients.rate_tier} (stashed on the request by
 * {@code TenantContextFilter}), falling back to {@code default}.
 *
 * Buckets are in-memory - {@code spring-boot-api} is single-instance. The
 * upgrade path for a horizontally-scaled deployment is a Redis-backed
 * bucket4j {@code ProxyManager} over the Lettuce connection already in the
 * stack; nothing else about this filter changes.
 *
 * Runs in the security chain (needs the authenticated {@code azp}), before
 * {@code IdempotencyFilter} so a throttled request never consumes an
 * idempotency claim. Servlet auto-registration is disabled - see
 * {@code SecurityConfig}.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String DEFAULT_TIER = "default";

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, TieredBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    private record TieredBucket(String tier, Bucket bucket) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String azp = currentAzp();
        if (azp == null) {
            chain.doFilter(request, response);
            return;
        }

        String tier = resolveTier(request);
        RateLimitProperties.Tier cfg = properties.getTiers().getOrDefault(tier, defaultTierConfig());

        TieredBucket tb = buckets.compute(azp, (k, current) ->
                (current != null && current.tier().equals(tier)) ? current : new TieredBucket(tier, newBucket(cfg)));

        ConsumptionProbe probe = tb.bucket().tryConsumeAndReturnRemaining(1);
        response.setHeader("X-RateLimit-Limit", String.valueOf(cfg.getCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));

        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1L,
                    (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            ProblemJson.write(response, 429, "rate-limit-exceeded",
                    "Rate limit exceeded for your API credentials. Retry after the seconds given in the Retry-After header.");
            return;
        }

        chain.doFilter(request, response);
    }

    private String resolveTier(HttpServletRequest request) {
        if (request.getAttribute(ApiClient.class.getName()) instanceof ApiClient apiClient) {
            String tier = apiClient.getRateTier();
            if (tier != null && !tier.isBlank()) {
                return tier;
            }
        }
        return DEFAULT_TIER;
    }

    private RateLimitProperties.Tier defaultTierConfig() {
        return properties.getTiers().getOrDefault(DEFAULT_TIER, new RateLimitProperties.Tier());
    }

    private static Bucket newBucket(RateLimitProperties.Tier cfg) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(cfg.getCapacity())
                .refillGreedy(cfg.getRefillTokens(), cfg.getRefillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private static String currentAzp() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            return (azp == null || azp.isBlank()) ? null : azp;
        }
        return null;
    }
}
