package com.bustix.api.v1;

import com.bustix.api.v1.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an RFC 9457 {@code application/problem+json} body directly to the
 * response - for the {@code /v1} servlet filters (rate limiting, idempotency,
 * the auth entry point) that reject a request before it reaches
 * {@link V1ExceptionHandler}. The shape matches what that advice produces:
 * {@code type/title/status/detail} plus a machine-readable {@code code} and
 * the request's {@code traceId}.
 */
public final class ProblemJson {

    private ProblemJson() {
    }

    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        // Bare "application/problem+json" with no ";charset=..." - matches what
        // V1ExceptionHandler's ProblemDetail responses emit. Writing UTF-8 bytes
        // straight to the output stream keeps the charset out of the header
        // (response.getWriter() + setCharacterEncoding would append it).
        response.setContentType("application/problem+json");
        String traceId = CorrelationIdFilter.current();
        String traceField = traceId != null ? ",\"traceId\":\"" + escape(traceId) + "\"" : "";
        String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"%s}",
                reasonPhrase(status), status, escape(detail), code, traceField);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            default -> "Error";
        };
    }
}
