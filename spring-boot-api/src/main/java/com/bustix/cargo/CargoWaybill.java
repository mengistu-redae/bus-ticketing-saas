package com.bustix.cargo;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A parcel/freight/excess-baggage shipment, modeled on
 * my-notes/ethiopian_bus_system_specs.md section 3.2 - see
 * my-notes/cargo_logistics_scope_v1.md for the full scoping rationale.
 * `created_at` (from BaseTenantEntity) doubles as the BRD's `issued_at`;
 * there's no separate column for it.
 */
@Entity
@Table(name = "cargo_waybills")
@Getter
@Setter
public class CargoWaybill extends BaseTenantEntity {

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    /** Optional: accompanied excess baggage tied to an existing passenger booking on the same trip. */
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "waybill_number", nullable = false, unique = true)
    private String waybillNumber;

    @Column(name = "consignor_name", nullable = false)
    private String consignorName;

    @Column(name = "consignor_phone", nullable = false)
    private String consignorPhone;

    @Column(name = "consignor_id_number")
    private String consignorIdNumber;

    @Column(name = "consignee_name", nullable = false)
    private String consigneeName;

    @Column(name = "consignee_phone", nullable = false)
    private String consigneePhone;

    /** Required (unlike a passenger's ID at booking time) - collect() has nothing to verify against otherwise. */
    @Column(name = "consignee_id_number", nullable = false)
    private String consigneeIdNumber;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "declared_value")
    private BigDecimal declaredValue;

    @Column(name = "gross_weight_kg", nullable = false)
    private BigDecimal grossWeightKg;

    /** GREATEST(grossWeightKg - rate.freeWeightThresholdKg, 0), snapshotted at write time - see the migration's own comment. */
    @Column(name = "excess_weight_kg", nullable = false)
    private BigDecimal excessWeightKg;

    @Column(name = "base_freight_charge", nullable = false)
    private BigDecimal baseFreightCharge;

    @Column(name = "weight_surcharge", nullable = false)
    private BigDecimal weightSurcharge;

    @Column(name = "handling_service_fee", nullable = false)
    private BigDecimal handlingServiceFee;

    @Column(name = "total_cargo_cost", nullable = false)
    private BigDecimal totalCargoCost;

    /** "unpaid" | "paid" | "collect_on_delivery" - plain string, same convention as status below. */
    @Column(name = "payment_status", nullable = false)
    private String paymentStatus = "unpaid";

    /** "issued" | "dispatched" | "arrived" | "collected" | "cancelled" - advanced only by explicit staff action, see CargoWaybillService. */
    @Column(nullable = false)
    private String status = "issued";

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "consignee_id_verified", nullable = false)
    private boolean consigneeIdVerified = false;

    @Column(name = "issued_by")
    private UUID issuedBy;
}
