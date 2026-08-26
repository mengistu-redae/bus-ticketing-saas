package com.bustix.cargo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
 * `createdAt` doubles as the BRD's `issued_at`; there's no separate column
 * for it.
 *
 * Deliberately does NOT extend BaseTenantEntity (unlike every other
 * tenant-scoped entity in this app) - since 2026-08-26's customer-request
 * flow, a "requested"-status waybill genuinely has no operator yet
 * (tenantId is null until a staff member picks a trip and confirms it via
 * CargoWaybillService.confirmAndIssue), but BaseTenantEntity.tenantId is
 * NOT NULL. Rather than weakening that constraint for every other
 * tenant-scoped entity, this one declares its own id/tenantId/createdAt
 * fields directly - same precedent as AppUser (nullable tenantId, doesn't
 * extend BaseTenantEntity either). Every existing derived-query repository
 * method (findByIdAndTenantId, findAllByTenantId, etc.) keeps working
 * unchanged since those are just method names against a tenantId property
 * that still exists, now declared locally instead of inherited.
 */
@Entity
@Table(name = "cargo_waybills")
@Getter
@Setter
public class CargoWaybill {

    @Id
    @GeneratedValue
    private UUID id;

    /** Null while status = "requested" - see this class's own javadoc. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Null while status = "requested" - a customer request may not have a trip picked yet. */
    @Column(name = "trip_id")
    private UUID tripId;

    /** Optional: accompanied excess baggage tied to an existing passenger booking on the same trip. */
    @Column(name = "booking_id")
    private UUID bookingId;

    /** Set only for a customer-initiated request (status starts "requested") - decoupled from bookingId, see CargoWaybillService.requestShipment. */
    @Column(name = "customer_user_id")
    private UUID customerUserId;

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

    /** Required once issued (collect() has nothing to verify against otherwise) - nullable only while status = "requested". */
    @Column(name = "consignee_id_number")
    private String consigneeIdNumber;

    /** Optional shipment-level summary - CargoWaybillItem rows (see V9) carry the real per-item detail. */
    @Column
    private String description;

    /** Snapshotted SUM of every CargoWaybillItem.declaredValue at write time - see CargoWaybillService. */
    @Column(name = "declared_value")
    private BigDecimal declaredValue;

    /** Snapshotted SUM of every CargoWaybillItem.grossWeightKg at write time - see CargoWaybillService. */
    @Column(name = "gross_weight_kg", nullable = false)
    private BigDecimal grossWeightKg;

    /** GREATEST(grossWeightKg - rate.freeWeightThresholdKg, 0), snapshotted at write time - see the migration's own comment. Null until priced at confirm-and-issue. */
    @Column(name = "excess_weight_kg")
    private BigDecimal excessWeightKg;

    @Column(name = "base_freight_charge")
    private BigDecimal baseFreightCharge;

    @Column(name = "weight_surcharge")
    private BigDecimal weightSurcharge;

    @Column(name = "handling_service_fee")
    private BigDecimal handlingServiceFee;

    @Column(name = "total_cargo_cost")
    private BigDecimal totalCargoCost;

    /** "unpaid" | "paid" | "collect_on_delivery" - plain string, same convention as status below. */
    @Column(name = "payment_status", nullable = false)
    private String paymentStatus = "unpaid";

    /** "requested" | "issued" | "dispatched" | "arrived" | "collected" | "cancelled" - advanced only by explicit action, see CargoWaybillService. "requested" only ever comes from a customer's own POST /api/my-shipments; every staff-created waybill starts at "issued". */
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
