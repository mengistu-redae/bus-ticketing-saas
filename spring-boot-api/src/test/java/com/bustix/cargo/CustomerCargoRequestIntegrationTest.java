package com.bustix.cargo;

import com.bustix.fleet.Route;
import com.bustix.operator.Operator;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/my-shipments (customer, CargoWaybillService.requestShipment)
 * -> POST /api/cargo/waybills/{id}/confirm-and-issue (staff,
 * CargoWaybillService.confirmAndIssue) - the two-phase customer-initiated
 * cargo flow added 2026-08-26. Separate test class from
 * CargoWaybillIntegrationTest since this covers a genuinely distinct
 * access pattern (a request has no tenant until confirmed), not just more
 * waybill-lifecycle cases.
 */
class CustomerCargoRequestIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CargoRateRepository cargoRateRepository;

    private void seedRate(UUID tenantId) {
        CargoRate rate = new CargoRate();
        rate.setTenantId(tenantId);
        rate.setFreeWeightThresholdKg(new BigDecimal("30.00"));
        rate.setBaseFreightCharge(new BigDecimal("200.00"));
        rate.setSurchargePerKg(new BigDecimal("10.00"));
        rate.setHandlingFee(new BigDecimal("50.00"));
        cargoRateRepository.save(rate);
    }

    private static final String REQUEST_BODY = """
            {
              "consignorName": "Customer Requester",
              "consignorPhone": "+251911111111",
              "consigneeName": "Requested Consignee",
              "consigneePhone": "+251922222222",
              "items": [{"description": "Two suitcases", "grossWeightKg": 20.0}]
            }
            """;

    @Test
    void customerCanSubmitAShipmentRequestWithNoTripAndNoPricingYet() throws Exception {
        mockMvc.perform(post("/api/my-shipments")
                        .with(asCustomer("customer-req-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("requested"))
                .andExpect(jsonPath("$.waybill.tripId").doesNotExist())
                .andExpect(jsonPath("$.waybill.totalCargoCost").doesNotExist())
                .andExpect(jsonPath("$.waybill.grossWeightKg").value(20.0))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void staffCannotSubmitAShipmentRequest() throws Exception {
        Operator operator = createOperator("cargo-req-role-" + UUID.randomUUID(), "Role Co");
        mockMvc.perform(post("/api/my-shipments")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffSeesThePendingRequestAndConfirmsAndIssuesIt() throws Exception {
        Operator operator = createOperator("cargo-req-confirm-" + UUID.randomUUID(), "Confirm Co");
        var bus = createBus(operator.getId(), "CR-1", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String createJson = mockMvc.perform(post("/api/my-shipments")
                        .with(asCustomer("customer-req-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(createJson).get("waybill").get("id").asText();

        mockMvc.perform(get("/api/cargo/requests")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.waybill.id=='" + waybillId + "')]").exists());

        // The staff single-waybill GET must also let a still-unclaimed
        // ("requested", tenantId null) waybill through - found live in the
        // browser: GET was still tenant-scoped via findByIdAndTenantId,
        // 404ing on every request before it was ever confirmed-and-issued,
        // even for the agent who'd go on to claim it.
        mockMvc.perform(get("/api/cargo/waybills/" + waybillId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("requested"));

        String confirmBody = """
                { "tripId": "%s", "consigneeIdNumber": "CONFIRMED-ID" }
                """.formatted(trip.getId());

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/confirm-and-issue")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("issued"))
                .andExpect(jsonPath("$.waybill.tripId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.waybill.consigneeIdNumber").value("CONFIRMED-ID"))
                // 20kg, under the 30kg threshold - no surcharge: 200 base + 50 handling.
                .andExpect(jsonPath("$.waybill.totalCargoCost").value(250.00));

        // Idempotent re-call: already issued, returns current state, doesn't 409.
        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/confirm-and-issue")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("issued"));

        // Full lifecycle continues normally post-issue - state machine untouched.
        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/dispatch")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("dispatched"));
    }

    @Test
    void confirmAndIssueFailsWithoutAConsigneeIdFromEitherSide() throws Exception {
        Operator operator = createOperator("cargo-req-noid-" + UUID.randomUUID(), "No Id Co");
        var bus = createBus(operator.getId(), "CR-2", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String createJson = mockMvc.perform(post("/api/my-shipments")
                        .with(asCustomer("customer-req-3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(createJson).get("waybill").get("id").asText();

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/confirm-and-issue")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"tripId\": \"" + trip.getId() + "\" }"))
                .andExpect(status().isConflict());
    }

    @Test
    void aCustomerCannotSeeAnotherCustomersRequest() throws Exception {
        String createJson = mockMvc.perform(post("/api/my-shipments")
                        .with(asCustomer("customer-req-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(createJson).get("waybill").get("id").asText();

        mockMvc.perform(get("/api/my-shipments/" + waybillId)
                        .with(asCustomer("customer-req-other")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/my-shipments")
                        .with(asCustomer("customer-req-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.waybill.id=='" + waybillId + "')]").exists());
    }
}
