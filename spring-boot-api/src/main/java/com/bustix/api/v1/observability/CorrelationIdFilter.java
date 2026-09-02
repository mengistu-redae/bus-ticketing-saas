package com.bustix.api.v1.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a correlation id on every request: the caller's {@code X-Request-Id}
 * if it looks sane, otherwise a generated one. It goes in the SLF4J MDC as
 * {@code requestId} (so it appears in every log line for the request) and is
 * echoed back in the {@code X-Request-Id} response header. {@code /v1} error
 * bodies surface it as {@code traceId} - see {@code V1ExceptionHandler} /
 * {@code ProblemJson}.
 *
 * A plain servlet-level filter ordered ahead of Spring Security's chain
 * (which sits at order -100), so even a request the security chain rejects
 * with a 401 still gets the id in its log line and response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-Id";
    private static final Pattern SANE = Pattern.compile("[A-Za-z0-9._\\-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SANE.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's correlation id, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
