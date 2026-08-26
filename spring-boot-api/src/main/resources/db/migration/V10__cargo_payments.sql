-- Cargo payments ledger integration (gap #2 - see CLAUDE.md's "Known
-- gaps"): reuses the existing `payments` table for waybill payments too,
-- rather than a parallel waybill_payments table. A payment now belongs to
-- exactly one of a booking or a waybill, never both, never neither -
-- enforced by the CHECK below at the DB level (not just application code),
-- so a stray direct-SQL insert can't violate it either.

ALTER TABLE payments ADD COLUMN waybill_id UUID REFERENCES cargo_waybills(id);
ALTER TABLE payments ALTER COLUMN booking_id DROP NOT NULL;
ALTER TABLE payments ADD CONSTRAINT chk_payments_exactly_one_owner
    CHECK ((booking_id IS NOT NULL) <> (waybill_id IS NOT NULL));

CREATE INDEX idx_payments_waybill ON payments(waybill_id);
