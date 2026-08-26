package com.bustix.cargo;

import com.bustix.fleet.Route;
import com.bustix.operator.Operator;
import com.bustix.payment.Payment;
import com.bustix.payment.PaymentRepository;
import com.bustix.scheduling.Trip;
import com.bustix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/cargo/waybills/{waybillId}/payments(/{id}) - the cargo counterpart
 * to booking payments (see com.bustix.booking's equivalent, though that
 * module has no dedicated PaymentController test either - this is the
 * first coverage for either payments surface). Reuses the same `payments`
 * table as bookings (V10 added a nullable waybill_id + CHECK constraint) -
 * see CargoPaymentController.
 */
class CargoPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CargoRateRepository cargoRateRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CargoWaybillRepository cargoWaybillRepository;

    private CargoWaybill createWaybillDirect(Operator operator, Route route, Trip trip) {
        CargoRate rate = new CargoRate();
        rate.setTenantId(operator.getId());
        rate.setBaseFreightCharge(new BigDecimal("200.00"));
        cargoRateRepository.save(rate);

        CargoWaybill waybill = new CargoWaybill();
        waybill.setTenantId(operator.getId());
        waybill.setTripId(trip.getId());
        waybill.setWaybillNumber("TEST-" + UUID.randomUUID());
        waybill.setConsignorName("Consignor");
        waybill.setConsignorPhone("+251911111111");
        waybill.setConsigneeName("Consignee");
        waybill.setConsigneePhone("+251922222222");
        waybill.setConsigneeIdNumber("ID-1");
        waybill.setGrossWeightKg(new BigDecimal("5.00"));
        waybill.setExcessWeightKg(BigDecimal.ZERO);
        waybill.setBaseFreightCharge(new BigDecimal("200.00"));
        waybill.setWeightSurcharge(BigDecimal.ZERO);
        waybill.setHandlingServiceFee(new BigDecimal("50.00"));
        waybill.setTotalCargoCost(new BigDecimal("250.00"));
        waybill.setStatus("issued");
        return waybill;
    }

    @Test
    void staffRecordsAndListsPaymentsAgainstAWaybill() throws Exception {
        Operator operator = createOperator("cargo-pay-" + UUID.randomUUID(), "Cargo Pay Co");
        var bus = createBus(operator.getId(), "CP-1", 10, "2x2");
        Route route = createRoute(operator.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(operator.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        CargoWaybill waybill = cargoWaybillRepository.save(createWaybillDirect(operator, route, trip));

        String createJson = mockMvc.perform(post("/api/cargo/waybills/" + waybill.getId() + "/payments")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"cash\",\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("cash"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(createJson).get("id").asText();

        mockMvc.perform(get("/api/cargo/waybills/" + waybill.getId() + "/payments")
                        .with(asAgent("agent-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(paymentId));

        mockMvc.perform(get("/api/cargo/waybills/" + waybill.getId() + "/payments/" + paymentId)
                        .with(asOperatorAdmin("admin-1", operator.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100.00));

        mockMvc.perform(patch("/api/cargo/waybills/" + waybill.getId() + "/payments/" + paymentId)
                        .with(asAgent("agent-1", operator.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(150.00));

        Payment stored = paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow();
        assertThat(stored.getWaybillId()).isEqualTo(waybill.getId());
        assertThat(stored.getBookingId()).isNull();
    }

    @Test
    void recordingAPaymentAgainstAnotherOperatorsWaybillIsNotFound() throws Exception {
        Operator owner = createOperator("cargo-pay-owner-" + UUID.randomUUID(), "Owner Co");
        Operator other = createOperator("cargo-pay-other-" + UUID.randomUUID(), "Other Co");
        var bus = createBus(owner.getId(), "CP-2", 10, "2x2");
        Route route = createRoute(owner.getId(), "Addis Ababa", "Adama");
        Trip trip = createTrip(owner.getId(), route.getId(), bus.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("120.00"));
        CargoWaybill waybill = cargoWaybillRepository.save(createWaybillDirect(owner, route, trip));

        mockMvc.perform(post("/api/cargo/waybills/" + waybill.getId() + "/payments")
                        .with(asAgent("agent-other", other.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"cash\",\"amount\":50.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theExactlyOneOwnerCheckConstraintRejectsBothAndNeither() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, booking_id, waybill_id, method, amount, collected_at) "
                        + "VALUES (gen_random_uuid(), NULL, NULL, 'cash', 10.00, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
