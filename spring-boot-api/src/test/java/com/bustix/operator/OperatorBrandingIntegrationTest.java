package com.bustix.operator;

import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET/PATCH /api/operator/branding - the per-operator branding surface
 * (its own endpoint, disjoint from the full-replace PATCH
 * /api/fleet/settings). GET is readable by AGENT (the staff SPA themes
 * itself from it); PATCH is OPERATOR_ADMIN only.
 */
class OperatorBrandingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void getWithNoRowFallsBackToTheOperatorsLegalName() throws Exception {
        Operator operator = createOperator("brand-default-" + UUID.randomUUID(), "Selam Bus Co");

        mockMvc.perform(get("/api/operator/branding")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Selam Bus Co"))
                .andExpect(jsonPath("$.brandColor").doesNotExist())
                .andExpect(jsonPath("$.accentColor").doesNotExist())
                .andExpect(jsonPath("$.logoUrl").doesNotExist());
    }

    @Test
    void patchSetsBrandingAndGetReflectsIt() throws Exception {
        Operator operator = createOperator("brand-set-" + UUID.randomUUID(), "Set Co");

        mockMvc.perform(patch("/api/operator/branding")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Selam Lines",
                                  "tagline": "On time, every time",
                                  "brandColor": "#0F766E",
                                  "accentColor": "#F59E0B",
                                  "logoUrl": "https://cdn.example.test/selam.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Selam Lines"))
                .andExpect(jsonPath("$.brandColor").value("#0F766E"));

        mockMvc.perform(get("/api/operator/branding")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Selam Lines"))
                .andExpect(jsonPath("$.tagline").value("On time, every time"))
                .andExpect(jsonPath("$.logoUrl").value("https://cdn.example.test/selam.png"));
    }

    @Test
    void invalidColourOrLogoUrlIsRejected() throws Exception {
        Operator operator = createOperator("brand-invalid-" + UUID.randomUUID(), "Invalid Co");

        mockMvc.perform(patch("/api/operator/branding")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brandColor\":\"teal\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/operator/branding")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logoUrl\":\"ftp://x/y.png\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agentCanReadButNotWriteBranding() throws Exception {
        Operator operator = createOperator("brand-role-" + UUID.randomUUID(), "Role Co");

        mockMvc.perform(get("/api/operator/branding")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/operator/branding")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/operator/branding")
                        .with(asCustomer("customer-1")))
                .andExpect(status().isForbidden());
    }
}
