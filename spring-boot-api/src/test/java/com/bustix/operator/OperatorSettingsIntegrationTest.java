package com.bustix.operator;

import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET/PATCH /api/fleet/settings - the per-operator settings singleton (see
 * OperatorSettingsController). Lazy row: GET works with no row and returns
 * the platform defaults; PATCH is a full replace of the override set, so a
 * null field clears an override back to the default.
 */
class OperatorSettingsIntegrationTest extends AbstractIntegrationTest {

    private Map<String, Object> allNull() {
        Map<String, Object> body = new HashMap<>();
        for (String k : new String[] {
                "vatRate", "reportingBufferMinutes", "rescheduleMinNoticeHours",
                "rescheduleFeeSelfService", "rescheduleFeeCounter",
                "supportPhone", "supportEmail", "supportAddress", "websiteUrl", "ticketFooterNote" }) {
            body.put(k, null);
        }
        body.put("rescheduleNotificationsEnabled", true);
        return body;
    }

    @Test
    void getWithNoRowReturnsPlatformDefaults() throws Exception {
        Operator operator = createOperator("settings-defaults-" + UUID.randomUUID(), "Defaults Co");

        mockMvc.perform(get("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides").doesNotExist())
                .andExpect(jsonPath("$.effective.vatRate").value(0.15))
                .andExpect(jsonPath("$.effective.reportingBufferMinutes").value(30))
                .andExpect(jsonPath("$.effective.rescheduleMinNoticeHours").value(12))
                .andExpect(jsonPath("$.effective.rescheduleNotificationsEnabled").value(true))
                .andExpect(jsonPath("$.defaults.vatRate").value(0.15));
    }

    @Test
    void patchCreatesTheRowAndOverridesTakeEffectWhileUnsetFieldsStayDefault() throws Exception {
        Operator operator = createOperator("settings-patch-" + UUID.randomUUID(), "Patch Co");

        Map<String, Object> body = allNull();
        body.put("vatRate", 0.10);
        body.put("rescheduleMinNoticeHours", 24);
        body.put("supportPhone", "+251911234567");

        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.vatRate").value(0.10))
                .andExpect(jsonPath("$.effective.vatRate").value(0.10))
                .andExpect(jsonPath("$.effective.rescheduleMinNoticeHours").value(24))
                .andExpect(jsonPath("$.effective.supportPhone").value("+251911234567"))
                // reportingBufferMinutes was left null -> still the platform default
                .andExpect(jsonPath("$.overrides.reportingBufferMinutes").doesNotExist())
                .andExpect(jsonPath("$.effective.reportingBufferMinutes").value(30));

        mockMvc.perform(get("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.vatRate").value(0.10));
    }

    @Test
    void patchWithAFieldBackToNullRevertsThatOverride() throws Exception {
        Operator operator = createOperator("settings-revert-" + UUID.randomUUID(), "Revert Co");

        Map<String, Object> set = allNull();
        set.put("vatRate", 0.05);
        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(set)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.vatRate").value(0.05));

        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(allNull())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.vatRate").doesNotExist())
                .andExpect(jsonPath("$.effective.vatRate").value(0.15));
    }

    @Test
    void agentAndCustomerCannotReadOrWriteSettings() throws Exception {
        Operator operator = createOperator("settings-role-" + UUID.randomUUID(), "Role Co");

        mockMvc.perform(get("/api/fleet/settings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/fleet/settings")
                        .with(asCustomer("customer-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(allNull())))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidValuesAreRejected() throws Exception {
        Operator operator = createOperator("settings-invalid-" + UUID.randomUUID(), "Invalid Co");

        Map<String, Object> badVat = allNull();
        badVat.put("vatRate", 1.5);
        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badVat)))
                .andExpect(status().isBadRequest());

        Map<String, Object> badPhone = allNull();
        badPhone.put("supportPhone", "0911234567");
        mockMvc.perform(patch("/api/fleet/settings")
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPhone)))
                .andExpect(status().isBadRequest());
    }
}
