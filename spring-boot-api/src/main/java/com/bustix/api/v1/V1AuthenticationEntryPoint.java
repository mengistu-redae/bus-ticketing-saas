package com.bustix.api.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 responses on {@code /v1} come back as {@code application/problem+json}
 * (matching {@link V1ExceptionHandler}); every other path keeps Spring
 * Security's default bearer-token entry point, so the BFF-fronted
 * {@code /api} surface is untouched.
 */
@Component
public class V1AuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (!request.getRequestURI().startsWith("/v1/")) {
            delegate.commence(request, response, authException);
            return;
        }
        response.setHeader("WWW-Authenticate", "Bearer");
        ProblemJson.write(response, 401, "unauthorized",
                "A valid OAuth2 bearer token is required. Obtain one with the client-credentials grant.");
    }
}
