package com.bustix.partner;

import com.bustix.operator.Operator;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-1 of the Partner API: a client-credentials token from a registered
 * {@code api_clients} row resolves, via its {@code azp} claim, to exactly one
 * operator's tenant - and a revoked row is a hard lockout. Plus the
 * platform_admin management surface at {@code /api/platform/partners}.
 *
 * The Keycloak side of provisioning ({@link KeycloakPartnerClient}) is
 * mocked - there's no Keycloak container in the integration stack, same as
 * {@code PlatformController}'s org-creation path is not integration-tested.
 */
class PartnerIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private KeycloakPartnerClient keycloakPartnerClient;

    @Test
    void partnerTokenResolvesToItsOwnOperatorsTenantOnAScopedEndpoint() throws Exception {
        Operator mine = createOperator("partner-mine-" + UUID.randomUUID(), "Partner Mine");
        Operator other = createOperator("partner-other-" + UUID.randomUUID(), "Partner Other");
        var busMine = createBus(mine.getId(), "PM-1", 10, "2x2");
        var routeMine = createRoute(mine.getId(), "Addis Ababa", "Adama");
        createTrip(mine.getId(), routeMine.getId(), busMine.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));

        var busOther = createBus(other.getId(), "PO-1", 10, "2x2");
        var routeOther = createRoute(other.getId(), "Addis Ababa", "Adama");
        createTrip(other.getId(), routeOther.getId(), busOther.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("140.00"));

        createApiClient(mine.getId(), "partner-mine-acme-123");

        mockMvc.perform(get("/api/fleet/trips").with(asPartner("partner-mine-acme-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tenantId").value(mine.getId().toString()));
    }

    @Test
    void revokedPartnerIsLockedOutOfTheWholeApi() throws Exception {
        Operator operator = createOperator("partner-revoked-" + UUID.randomUUID(), "Revoked Co");
        ApiClient client = createApiClient(operator.getId(), "partner-revoked-9");
        client.setStatus("revoked");
        apiClientRepository.save(client);

        mockMvc.perform(get("/api/fleet/trips").with(asPartner("partner-revoked-9")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivatedOperatorLocksOutItsPartnerToo() throws Exception {
        Operator operator = createOperator("partner-deact-" + UUID.randomUUID(), "Deactivated Co");
        operator.setStatus("inactive");
        operatorRepository.save(operator);
        createApiClient(operator.getId(), "partner-deact-3");

        mockMvc.perform(get("/api/fleet/trips").with(asPartner("partner-deact-3")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managementSurfaceIsPlatformAdminOnly() throws Exception {
        Operator operator = createOperator("partner-mgmt-" + UUID.randomUUID(), "Mgmt Co");
        createApiClient(operator.getId(), "partner-mgmt-list-1");

        mockMvc.perform(get("/api/platform/partners").with(asPlatformAdmin("pa-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keycloakClientId=='partner-mgmt-list-1')]").exists());

        mockMvc.perform(get("/api/platform/partners").with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPartnerProvisionsAKeycloakClientAndPersistsTheBinding() throws Exception {
        Operator operator = createOperator("partner-create-" + UUID.randomUUID(), "Create Co");
        when(keycloakPartnerClient.createConfidentialClient(anyString(), anyString())).thenReturn("s3cr3t-value");

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Acme Travel",
                "operatorId", operator.getId().toString(),
                "scopes", List.of("trips:read", "bookings:write")));

        mockMvc.perform(post("/api/platform/partners").with(asPlatformAdmin("pa-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("s3cr3t-value"))
                .andExpect(jsonPath("$.operatorId").value(operator.getId().toString()))
                .andExpect(jsonPath("$.scopes").value("trips:read bookings:write"));

        assertThat(apiClientRepository.findAll())
                .anyMatch(c -> c.getName().equals("Acme Travel")
                        && c.getTenantId().equals(operator.getId())
                        && c.getStatus().equals("active"));
    }
}
