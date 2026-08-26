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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST/GET/PATCH /api/cargo/waybills(/{id}) - the first integration
 * coverage for this module (see CLAUDE.md's Cargo & Logistics section: it
 * had only live curl/browser verification before), focused on the
 * multi-item waybill shape added in this session - see CargoWaybillItem/
 * WaybillWithItems.
 */
class CargoWaybillIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CargoRateRepository cargoRateRepository;

    @Autowired
    private CargoWaybillItemRepository cargoWaybillItemRepository;

    private CargoRate seedRate(UUID tenantId) {
        CargoRate rate = new CargoRate();
        rate.setTenantId(tenantId);
        rate.setFreeWeightThresholdKg(new BigDecimal("30.00"));
        rate.setBaseFreightCharge(new BigDecimal("200.00"));
        rate.setSurchargePerKg(new BigDecimal("10.00"));
        rate.setHandlingFee(new BigDecimal("50.00"));
        return cargoRateRepository.save(rate);
    }

    private static String itemsJson(String... descriptionsAndWeights) {
        // descriptionsAndWeights: alternating description, weight pairs.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < descriptionsAndWeights.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("{\"description\":\"").append(descriptionsAndWeights[i])
                    .append("\",\"grossWeightKg\":").append(descriptionsAndWeights[i + 1]).append("}");
        }
        return sb.append("]").toString();
    }

    @Test
    void createsAWaybillWithMultipleItemsAndPricesOffTheirAggregateWeight() throws Exception {
        Operator operator = createOperator("cargo-multi-" + UUID.randomUUID(), "Cargo Multi Co");
        var bus = createBus(operator.getId(), "CG-1", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        // Two items: 20kg + 25kg = 45kg total, 15kg over the 30kg threshold.
        // 15 * 10.00 surcharge/kg = 150.00; + 200.00 base + 50.00 handling = 400.00.
        String body = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor One",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee One",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-1",
                  "description": "Two boxes",
                  "items": %s
                }
                """.formatted(trip.getId(), itemsJson("Textiles", "20.0", "Electronics", "25.0"));

        String json = mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("issued"))
                .andExpect(jsonPath("$.waybill.grossWeightKg").value(45.0))
                .andExpect(jsonPath("$.waybill.excessWeightKg").value(15.0))
                .andExpect(jsonPath("$.waybill.totalCargoCost").value(400.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        String waybillId = objectMapper.readTree(json).get("waybill").get("id").asText();
        assertThat(cargoWaybillItemRepository.findAllByWaybillId(UUID.fromString(waybillId))).hasSize(2);

        mockMvc.perform(get("/api/cargo/waybills/" + waybillId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].description").exists());
    }

    @Test
    void creatingAWaybillWithNoItemsIsRejected() throws Exception {
        Operator operator = createOperator("cargo-empty-" + UUID.randomUUID(), "Cargo Empty Co");
        var bus = createBus(operator.getId(), "CG-2", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String body = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-1",
                  "items": []
                }
                """.formatted(trip.getId());

        mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aProhibitedItemInAnySingleLineItemIsRejectedEvenWithAnOtherwiseCleanShipment() throws Exception {
        Operator operator = createOperator("cargo-prohibited-" + UUID.randomUUID(), "Cargo Prohibited Co");
        var bus = createBus(operator.getId(), "CG-3", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String body = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-1",
                  "items": %s
                }
                """.formatted(trip.getId(), itemsJson("Clothes", "5.0", "Live goat", "10.0"));

        mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchingItemsPreDispatchReplacesTheWholeSetAndRecalculatesPricing() throws Exception {
        Operator operator = createOperator("cargo-patch-" + UUID.randomUUID(), "Cargo Patch Co");
        var bus = createBus(operator.getId(), "CG-4", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String createBody = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-1",
                  "items": %s
                }
                """.formatted(trip.getId(), itemsJson("Textiles", "10.0"));

        String json = mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(json).get("waybill").get("id").asText();

        // Under the 30kg threshold - no surcharge yet, total = base + handling = 250.00.
        String patchBody = """
                { "items": %s }
                """.formatted(itemsJson("Replacement item", "5.0"));

        mockMvc.perform(patch("/api/cargo/waybills/" + waybillId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.grossWeightKg").value(5.0))
                .andExpect(jsonPath("$.waybill.totalCargoCost").value(250.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Replacement item"));

        assertThat(cargoWaybillItemRepository.findAllByWaybillId(UUID.fromString(waybillId))).hasSize(1);
    }

    @Test
    void patchingItemsAfterDispatchIsRejectedButPaymentStatusStillGoesThrough() throws Exception {
        Operator operator = createOperator("cargo-frozen-" + UUID.randomUUID(), "Cargo Frozen Co");
        var bus = createBus(operator.getId(), "CG-5", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String createBody = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-1",
                  "items": %s
                }
                """.formatted(trip.getId(), itemsJson("Textiles", "10.0"));

        String json = mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(json).get("waybill").get("id").asText();

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/dispatch")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/cargo/waybills/" + waybillId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": " + itemsJson("Should not apply", "1.0") + " }"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/cargo/waybills/" + waybillId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"paymentStatus\": \"paid\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.paymentStatus").value("paid"));
    }

    @Test
    void fullLifecycleIsUnaffectedByItemCount() throws Exception {
        Operator operator = createOperator("cargo-lifecycle-" + UUID.randomUUID(), "Cargo Lifecycle Co");
        var bus = createBus(operator.getId(), "CG-6", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        seedRate(operator.getId());

        String createBody = """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor",
                  "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee",
                  "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "PICKUP-ID",
                  "items": %s
                }
                """.formatted(trip.getId(), itemsJson("Item A", "5.0", "Item B", "5.0", "Item C", "5.0"));

        String json = mockMvc.perform(post("/api/cargo/waybills")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String waybillId = objectMapper.readTree(json).get("waybill").get("id").asText();

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/dispatch")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("dispatched"));

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/arrive")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("arrived"));

        mockMvc.perform(post("/api/cargo/waybills/" + waybillId + "/collect")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presentedIdNumber\":\"PICKUP-ID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybill.status").value("collected"))
                .andExpect(jsonPath("$.items.length()").value(3));
    }
}
