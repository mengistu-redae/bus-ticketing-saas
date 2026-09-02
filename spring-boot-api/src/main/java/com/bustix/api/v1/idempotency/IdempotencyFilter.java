package com.bustix.api.v1.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * Safe-retry enforcement for the {@code /v1} write surface (see V16). Every
 * {@code POST}/{@code PATCH} under {@code /v1} must carry an
 * {@code Idempotency-Key} header:
 *
 * <ul>
 *   <li>first time seen for this API client &rarr; the request runs, and its
 *       response (status + body) is recorded;</li>
 *   <li>seen again with the <em>same</em> body &rarr; the recorded response
 *       is replayed, the handler never runs again;</li>
 *   <li>seen again with a <em>different</em> body &rarr; {@code 422};</li>
 *   <li>seen again while the first is still in flight &rarr; {@code 409}.</li>
 * </ul>
 *
 * Errors here are emitted as {@code application/problem+json} to match
 * {@link com.bustix.api.v1.V1ExceptionHandler}. Registered in the security
 * chain (needs the authenticated {@code azp}); a {@code FilterRegistrationBean}
 * disables servlet-level auto-registration - see {@code SecurityConfig}.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PATCH");
    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyRecordRepository repository;

    public IdempotencyFilter(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/") || !MUTATING.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String apiClientId = currentAzp();
        if (apiClientId == null) {
            // No authenticated partner - let the security chain reject it.
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            writeProblem(response, 400, "idempotency-key-required",
                    "This endpoint requires an Idempotency-Key header so a retried request is not processed twice.");
            return;
        }
        key = key.trim();

        byte[] body = request.getInputStream().readAllBytes();
        String hash = sha256Hex(body);

        var existing = repository.findByApiClientIdAndIdempotencyKey(apiClientId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(hash)) {
                writeProblem(response, 422, "idempotency-key-reused",
                        "This Idempotency-Key was already used with a different request body.");
                return;
            }
            if (record.getResponseStatus() == null) {
                writeProblem(response, 409, "idempotency-request-in-progress",
                        "A request with this Idempotency-Key is still being processed. Retry shortly.");
                return;
            }
            response.setStatus(record.getResponseStatus());
            response.setContentType("application/json");
            response.setHeader("Idempotency-Replayed", "true");
            if (record.getResponseBody() != null) {
                response.getWriter().write(record.getResponseBody());
            }
            return;
        }

        // Claim the key. The unique (api_client_id, idempotency_key) makes a
        // concurrent duplicate lose this race with a constraint violation.
        IdempotencyRecord claim = new IdempotencyRecord();
        claim.setApiClientId(apiClientId);
        claim.setIdempotencyKey(key);
        claim.setMethod(request.getMethod());
        claim.setPath(request.getRequestURI());
        claim.setRequestHash(hash);
        try {
            claim = repository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException race) {
            writeProblem(response, 409, "idempotency-request-in-progress",
                    "A request with this Idempotency-Key is already being processed.");
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrappedResponse);
        } finally {
            int status = wrappedResponse.getStatus();
            byte[] responseBody = wrappedResponse.getContentAsByteArray();
            wrappedResponse.copyBodyToResponse();

            if (status >= 200 && status < 500) {
                claim.setResponseStatus(status);
                claim.setResponseBody(new String(responseBody, StandardCharsets.UTF_8));
                try {
                    repository.save(claim);
                } catch (RuntimeException e) {
                    log.warn("Could not record idempotent response for key {}", key, e);
                }
            } else {
                // Transient failure - drop the claim so a retry starts fresh.
                try {
                    repository.delete(claim);
                } catch (RuntimeException e) {
                    log.warn("Could not release idempotency claim for key {}", key, e);
                }
            }
        }
    }

    private static String currentAzp() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String azp = jwt.getClaimAsString("azp");
            return azp == null || azp.isBlank() ? null : azp;
        }
        return null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeProblem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            default -> "Error";
        };
        response.getWriter().write(String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}",
                reason, status, detail.replace("\"", "\\\""), code));
    }

    /** Re-readable request so the downstream handler still sees the body the filter already consumed. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return stream.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
