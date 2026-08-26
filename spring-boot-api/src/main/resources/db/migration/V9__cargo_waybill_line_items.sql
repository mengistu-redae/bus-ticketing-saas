-- Multi-item waybill support (Cargo & Logistics gap #1 - see CLAUDE.md's
-- "Known gaps" and my-notes/cargo_logistics_scope_v1.md's open schema
-- note): a shipment may contain several distinct items, each its own
-- description/quantity/weight, rather than one flat set of fields on
-- cargo_waybills.

CREATE TABLE cargo_waybill_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    waybill_id       UUID NOT NULL REFERENCES cargo_waybills(id),
    description      TEXT NOT NULL,
    quantity         INTEGER NOT NULL DEFAULT 1,
    declared_value   NUMERIC(10,2),
    gross_weight_kg  NUMERIC(6,2) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cargo_waybill_items_waybill ON cargo_waybill_items(waybill_id);

-- Backfill: one item row per existing waybill's flat fields, before those
-- columns are dropped/relaxed below. Safe even if cargo_waybills is empty
-- (a young table, likely dev-only data) - correctness over convenience.
INSERT INTO cargo_waybill_items (waybill_id, description, quantity, declared_value, gross_weight_kg)
SELECT id, description, quantity, declared_value, gross_weight_kg
FROM cargo_waybills;

-- description survives on cargo_waybills as an optional shipment-level
-- summary (nullable now - items carry the real per-item detail).
-- quantity is dropped entirely - a single count summed across
-- heterogeneous items isn't meaningful. declared_value/gross_weight_kg
-- and the pricing columns are left in shape unchanged - only their
-- *source* changes going forward (now summed from items at write time
-- instead of taken directly off a flat request field).
ALTER TABLE cargo_waybills ALTER COLUMN description DROP NOT NULL;
ALTER TABLE cargo_waybills DROP COLUMN quantity;
