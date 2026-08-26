-- Customer-initiated cargo requests (gap #3 - see CLAUDE.md's "Known
-- gaps"): a registered customer can submit a shipment request
-- (status = 'requested') before it's staff-reviewed and priced. Two-phase
-- flow: requested -> issued (via POST /api/cargo/waybills/{id}/confirm-
-- and-issue) -> the existing dispatched/arrived/collected/cancelled
-- machine, untouched from here on.

ALTER TABLE cargo_waybills ADD COLUMN customer_user_id UUID REFERENCES app_user(id);
CREATE INDEX idx_cargo_waybills_customer ON cargo_waybills(customer_user_id);

-- A request has no operator yet - staff assigns one (via the trip they
-- pick) at confirm-and-issue time. Every existing/staff-created waybill
-- already has tenant_id set, so this is a pure widening, no backfill
-- needed.
ALTER TABLE cargo_waybills ALTER COLUMN tenant_id DROP NOT NULL;

-- A request has no trip chosen yet either - the customer may not know
-- which bus they're taking when they submit the request.
ALTER TABLE cargo_waybills ALTER COLUMN trip_id DROP NOT NULL;

-- Pricing isn't computed until confirm-and-issue - a request carries only
-- customer-declared item estimates, no cargo_rates lookup happens at
-- request time.
ALTER TABLE cargo_waybills ALTER COLUMN excess_weight_kg DROP NOT NULL;
ALTER TABLE cargo_waybills ALTER COLUMN base_freight_charge DROP NOT NULL;
ALTER TABLE cargo_waybills ALTER COLUMN weight_surcharge DROP NOT NULL;
ALTER TABLE cargo_waybills ALTER COLUMN handling_service_fee DROP NOT NULL;
ALTER TABLE cargo_waybills ALTER COLUMN total_cargo_cost DROP NOT NULL;

-- consignee_id_number was NOT NULL because collect() needs it on file - a
-- customer request may not know the final consignee ID at request time
-- either (e.g. requesting on someone else's behalf). Widened for the same
-- reason as trip_id; confirm-and-issue is expected to fill it in if the
-- customer left it blank, and collect()'s existing on-file check already
-- treats a blank/null value as an automatic mismatch, no new code needed
-- there.
ALTER TABLE cargo_waybills ALTER COLUMN consignee_id_number DROP NOT NULL;
