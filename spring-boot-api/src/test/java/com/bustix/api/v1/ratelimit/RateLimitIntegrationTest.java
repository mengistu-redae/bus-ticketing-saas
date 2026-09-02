package com.bustix.api.v1.ratelimit;

import com.bustix.operator.Operator;
import com.bustix.partner.ApiClient;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-4: per-partner token-bucket rate limiting on {@code /v1}. A tiny
 * {@code default} tier here (3 requests, never refilling within the test)
 * lets a handful of calls exhaust it.
 */
@TestPropertySource(properties = {
        "bustix.api.rate-limit.tiers.default.capacity=3",
        "bustix.api.rate-limit.tiers.default.refill-tokens=3",
        "bustix.api.rate-limit.tiers.default.refill-period=1h"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private void search(String clientId, org.springframework.test.web.servlet.ResultMatcher... matchers) throws Exception {
        var result = mockMvc.perform(get("/v1/trips")
                .param("origin", "Nowhere").param("destination", "Elsewhere")
                .with(asPartner(clientId)));
        for (var m : matchers) {
            result.andExpect(m);
        }
    }

    @Test
    void aPartnerIsThrottledOnceItsTierBudgetIsSpent() throws Exception {
        Operator operator = createOperator("rl-" + UUID.randomUUID(), "RL Co");
        String clientId = "rl-" + UUID.randomUUID();
        createApiClient(operator.getId(), clientId);

        for (int i = 0; i < 3; i++) {
            search(clientId, status().isOk(), header().exists("X-RateLimit-Limit"));
        }

        search(clientId,
                status().isTooManyRequests(),
                header().exists("Retry-After"),
                header().string("X-RateLimit-Remaining", "0"),
                jsonPath("$.code").value("rate-limit-exceeded"));
    }

    @Test
    void partnersHaveIndependentBudgets() throws Exception {
        Operator operator = createOperator("rl-ind-" + UUID.randomUUID(), "RL Ind Co");
        String a = "rl-a-" + UUID.randomUUID();
        String b = "rl-b-" + UUID.randomUUID();
        createApiClient(operator.getId(), a);
        createApiClient(operator.getId(), b);

        for (int i = 0; i < 3; i++) {
            search(a, status().isOk());
        }
        search(a, status().isTooManyRequests());

        // b's bucket is untouched
        search(b, status().isOk());
    }

    @Test
    void aTrustedTierPartnerGetsABiggerBudget() throws Exception {
        Operator operator = createOperator("rl-t-" + UUID.randomUUID(), "RL Trusted Co");
        String clientId = "rl-t-" + UUID.randomUUID();
        ApiClient client = createApiClient(operator.getId(), clientId);
        client.setRateTier("trusted");
        apiClientRepository.save(client);

        // "trusted" keeps its application.yml budget (600) - not throttled by 4 calls
        for (int i = 0; i < 4; i++) {
            search(clientId, status().isOk());
        }
    }
}
