package com.bustix.api.v1;

import com.bustix.cargo.CargoRate;
import com.bustix.cargo.CargoRateRepository;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-2c: the partner-facing {@code /v1/waybills} surface - create + price,
 * read, lifecycle (dispatch/arrive/collect), pre-dispatch cancel;
 * tenant-scoped; {@code waybills:read}/{@code waybills:write} scoped.
 */
class V1WaybillIntegrationTest extends AbstractIntegrationTest {

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

    private Trip seedTrip(Operator operator) {
        var bus = createBus(operator.getId(), "V1W-" + UUID.randomUUID().toString().substring(0, 6), 10, "2x2");
        var route = createRoute(operator.getId(), "Addis Ababa", "Dire Dawa");
        return createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(3, ChronoUnit.DAYS), new BigDecimal("120.00"));
    }

    private String createBody(UUID tripId) {
        return """
                {
                  "tripId": "%s",
                  "consignorName": "Consignor One", "consignorPhone": "+251911111111",
                  "consigneeName": "Consignee One", "consigneePhone": "+251922222222",
                  "consigneeIdNumber": "ID-9",
                  "description": "One box",
                  "items": [ { "description": "Books", "grossWeightKg": 45.0 } ]
                }
                """.formatted(tripId);
    }

    @Test
    void createReadLifecycle() throws Exception {
        Operator operator = createOperator("v1w-" + UUID.randomUUID(), "V1W Co");
        createApiClient(operator.getId(), "v1w-acme");
        seedRate(operator.getId());
        Trip trip = seedTrip(operator);

        // 45kg -> 15kg over 30kg threshold -> 150 surcharge + 200 base + 50 handling = 400 total
        String created = mockMvc.perform(post("/v1/waybills").with(asPartner("v1w-acme", "waybills:read", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(trip.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("issued"))
                .andExpect(jsonPath("$.operatorId").value(operator.getId().toString()))
                .andExpect(jsonPath("$.grossWeightKg").value(45.0))
                .andExpect(jsonPath("$.totalCargoCost").value(400.0))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.waybillNumber").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID waybillId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(get("/v1/waybills").with(asPartner("v1w-acme", "waybills:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(waybillId.toString()));

        mockMvc.perform(post("/v1/waybills/{id}/dispatch", waybillId)
                        .with(asPartner("v1w-acme", "waybills:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dispatched"))
                .andExpect(jsonPath("$.dispatchedAt").isNotEmpty());

        mockMvc.perform(post("/v1/waybills/{id}/arrive", waybillId)
                        .with(asPartner("v1w-acme", "waybills:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("arrived"));

        // wrong ID at collect -> 409
        mockMvc.perform(post("/v1/waybills/{id}/collect", waybillId)
                        .with(asPartner("v1w-acme", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"presentedIdNumber\":\"WRONG\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/v1/waybills/{id}/collect", waybillId)
                        .with(asPartner("v1w-acme", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"presentedIdNumber\":\"ID-9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("collected"));
    }

    @Test
    void createWithoutACargoRateIs400() throws Exception {
        Operator operator = createOperator("v1w-norate-" + UUID.randomUUID(), "NoRate Co");
        createApiClient(operator.getId(), "v1w-norate");
        Trip trip = seedTrip(operator);

        mockMvc.perform(post("/v1/waybills").with(asPartner("v1w-norate", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(trip.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prohibitedItemDescriptionIs400() throws Exception {
        Operator operator = createOperator("v1w-prohib-" + UUID.randomUUID(), "Prohib Co");
        createApiClient(operator.getId(), "v1w-prohib");
        seedRate(operator.getId());
        Trip trip = seedTrip(operator);

        String body = """
                {
                  "tripId": "%s",
                  "consignorName": "C", "consignorPhone": "+251911111111",
                  "consigneeName": "D", "consigneePhone": "+251922222222", "consigneeIdNumber": "ID-1",
                  "items": [ { "description": "live goat", "grossWeightKg": 20.0 } ]
                }
                """.formatted(trip.getId());

        mockMvc.perform(post("/v1/waybills").with(asPartner("v1w-prohib", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelPreDispatchComputesARefund() throws Exception {
        Operator operator = createOperator("v1w-cancel-" + UUID.randomUUID(), "Cancel Co");
        createApiClient(operator.getId(), "v1w-cancel");
        seedRate(operator.getId());
        Trip trip = seedTrip(operator);

        String created = mockMvc.perform(post("/v1/waybills").with(asPartner("v1w-cancel", "waybills:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(trip.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID waybillId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/v1/waybills/{id}/cancel", waybillId)
                        .with(asPartner("v1w-cancel", "waybills:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.refundAmount").exists());
    }

    @Test
    void readScopeCannotWrite() throws Exception {
        Operator operator = createOperator("v1w-scope-" + UUID.randomUUID(), "Scope Co");
        createApiClient(operator.getId(), "v1w-scope");
        seedRate(operator.getId());
        Trip trip = seedTrip(operator);

        mockMvc.perform(get("/v1/waybills").with(asPartner("v1w-scope", "waybills:read")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/waybills").with(asPartner("v1w-scope", "waybills:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(trip.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotReadAnotherOperatorsWaybill() throws Exception {
        Operator mine = createOperator("v1w-mine-" + UUID.randomUUID(), "Mine");
        Operator other = createOperator("v1w-other-" + UUID.randomUUID(), "Other");
        createApiClient(mine.getId(), "v1w-mine-acme");
        seedRate(other.getId());
        Trip otherTrip = seedTrip(other);

        // The other operator issues a waybill through its own staff endpoint.
        String created = mockMvc.perform(post("/api/cargo/waybills")
                        .with(asOperatorAdmin("other-admin", other.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(otherTrip.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID otherWaybillId = UUID.fromString(objectMapper.readTree(created).get("waybill").get("id").asText());

        mockMvc.perform(get("/v1/waybills/{id}", otherWaybillId).with(asPartner("v1w-mine-acme", "waybills:read")))
                .andExpect(status().isNotFound());
    }
}
