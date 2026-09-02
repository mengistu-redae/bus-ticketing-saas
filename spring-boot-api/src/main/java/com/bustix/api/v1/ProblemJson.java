package com.bustix.api.v1;

import com.bustix.api.v1.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

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
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String traceId = CorrelationIdFilter.current();
        String traceField = traceId != null ? ",\"traceId\":\"" + escape(traceId) + "\"" : "";
        response.getWriter().write(String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"%s}",
                reasonPhrase(status), status, escape(detail), code, traceField));
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
