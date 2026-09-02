package com.bustix.api.v1.observability;

import com.bustix.operator.Operator;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** WS-6: correlation ids on every response, and problem+json 401s on /v1. */
class V1ObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesRegex("[A-Za-z0-9._-]{1,128}")));
    }

    @Test
    void aCallerSuppliedRequestIdIsEchoedBack() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "req-abc-123"))
                .andExpect(header().string("X-Request-Id", "req-abc-123"));
    }

    @Test
    void v1WithoutATokenIsAProblemJson401WithTraceId() throws Exception {
        mockMvc.perform(get("/v1/trips").param("origin", "a").param("destination", "b"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aScopeDeniedResponseCarriesTheTraceId() throws Exception {
        Operator operator = createOperator("obs-" + UUID.randomUUID(), "Obs Co");
        createApiClient(operator.getId(), "obs-acme");

        // trips:read-only token -> GET /v1/bookings needs bookings:read
        mockMvc.perform(get("/v1/bookings").with(asPartner("obs-acme", "trips:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient-scope"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
