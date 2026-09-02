package com.bustix.api.v1;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Writes an RFC 9457 {@code application/problem+json} body directly to the
 * response - for the {@code /v1} servlet filters (rate limiting, idempotency)
 * that reject a request before it reaches {@link V1ExceptionHandler}. The
 * shape matches what that advice produces: {@code type/title/status/detail}
 * plus a machine-readable {@code code}.
 */
public final class ProblemJson {

    private ProblemJson() {
    }

    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}",
                reasonPhrase(status), status, escape(detail), code));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            default -> "Error";
        };
    }
}
