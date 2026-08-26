-- Cargo & Logistics Module v1 (my-notes/ethiopian_bus_system_specs.md
-- section 3, scoped down per my-notes/cargo_logistics_scope_v1.md - read
-- that doc for the reasoning behind every decision below, not just the
-- "what").

-- Operator/route-configurable freight pricing, shaped exactly like
-- refund_policies: route_id NULL = operator-wide default, a specific
-- route_id overrides it for that route only. Unlike refund_policies,
-- CargoWaybillService treats "no rate configured" as a hard block on
-- creating a waybill (400), not a safe zero-cost fallback - a free
-- shipment isn't the safe default the way a 0% refund is.
CREATE TABLE cargo_rates (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL REFERENCES operators(id),
    route_id                  UUID REFERENCES routes(id), -- NULL = operator-wide default
    free_weight_threshold_kg  NUMERIC(5,2) NOT NULL DEFAULT 30.00,
    base_freight_charge       NUMERIC(10,2) NOT NULL,
    surcharge_per_kg          NUMERIC(10,2) NOT NULL DEFAULT 10.00,
    handling_fee              NUMERIC(10,2) NOT NULL DEFAULT 50.00,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cargo_rates_tenant ON cargo_rates(tenant_id);

CREATE TABLE cargo_waybills (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES operators(id),
    trip_id                UUID NOT NULL REFERENCES trips(id),
    booking_id             UUID REFERENCES bookings(id), -- optional: accompanied excess baggage on an existing passenger booking
    waybill_number         VARCHAR(32) NOT NULL UNIQUE,

    consignor_name         VARCHAR(255) NOT NULL,
    consignor_phone        VARCHAR(20) NOT NULL,
    consignor_id_number    VARCHAR(64),
    consignee_name         VARCHAR(255) NOT NULL,
    consignee_phone        VARCHAR(20) NOT NULL,
    -- NOT NULL, unlike booking_seats.passenger_id_number: the consignee's ID
    -- must be known up front so the collect-time check
    -- (CargoWaybillService.collect) has something on file to verify against
    -- - there's no "checked later at boarding" equivalent for a pickup.
    consignee_id_number    VARCHAR(64) NOT NULL,

    description            TEXT NOT NULL,
    quantity                INTEGER NOT NULL DEFAULT 1,
    declared_value         NUMERIC(10,2),
    gross_weight_kg        NUMERIC(6,2) NOT NULL,
    -- GREATEST(gross_weight_kg - cargo_rates.free_weight_threshold_kg, 0) at
    -- the time of creation/correction - snapshotted here (not recomputed
    -- from cargo_rates on every read) so a later change to an operator's
    -- rate config never retroactively changes an already-issued waybill's
    -- charges, same principle as trips.price being copied onto a booking.
    excess_weight_kg       NUMERIC(6,2) NOT NULL,

    base_freight_charge    NUMERIC(10,2) NOT NULL,
    weight_surcharge       NUMERIC(10,2) NOT NULL,
    handling_service_fee   NUMERIC(10,2) NOT NULL,
    total_cargo_cost       NUMERIC(10,2) NOT NULL,
    payment_status         VARCHAR(20) NOT NULL DEFAULT 'unpaid', -- unpaid|paid|collect_on_delivery

    status                 VARCHAR(20) NOT NULL DEFAULT 'issued', -- issued|dispatched|arrived|collected|cancelled
    -- "issued_at" from the BRD's logistics_timestamps is just created_at
    -- below (a waybill's row is created at the moment it's issued) - no
    -- separate column, same as every other BaseTenantEntity-backed table.
    dispatched_at          TIMESTAMPTZ,
    arrived_at             TIMESTAMPTZ,
    collected_at           TIMESTAMPTZ,
    -- Flipped true at collect-time once the presented ID matches
    -- consignee_id_number - see CargoWaybillService.collect.
    consignee_id_verified  BOOLEAN NOT NULL DEFAULT false,

    issued_by              UUID REFERENCES app_user(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cargo_waybills_tenant ON cargo_waybills(tenant_id);
CREATE INDEX idx_cargo_waybills_trip ON cargo_waybills(trip_id);
-- Backs both the staff GET-by-id-style flows and the public track-by-number
-- path (GET /api/cargo/track/{waybillNumber}) - see CargoWaybillController.
CREATE UNIQUE INDEX idx_cargo_waybills_number ON cargo_waybills(waybill_number);

-- Mirrors cancellations - its own audit table rather than overloading the
-- booking-shaped cancellations table, since a waybill isn't a booking.
CREATE TABLE cargo_waybill_cancellations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    waybill_id      UUID NOT NULL REFERENCES cargo_waybills(id),
    cancelled_by    UUID REFERENCES app_user(id),
    reason          VARCHAR(255),
    refund_amount   NUMERIC(10,2) NOT NULL,
    refunded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
