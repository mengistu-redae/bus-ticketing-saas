-- Brings the schema in line with a real passenger ticket (see CLAUDE.md's
-- "Ticketing details" note for the source ticket this was modeled on):
-- human-readable ticket number/PNR, named passengers per seat, a VAT
-- breakdown on the fare, operator TIN, route terminal detail, and a
-- payment transaction reference. All additive - no existing column is
-- dropped or reinterpreted except total_amount, whose meaning changes from
-- "flat seat-count x price" to "subtotal + tax" going forward (existing
-- rows are backfilled with tax_amount = 0, not retroactively taxed).

ALTER TABLE operators ADD COLUMN tin VARCHAR(30);

ALTER TABLE routes ADD COLUMN origin_terminal VARCHAR(120);
ALTER TABLE routes ADD COLUMN destination_terminal VARCHAR(120);

-- Per-seat passenger identity - a booking can cover several seats, each for
-- a different named passenger. ID/passport is what's checked at boarding,
-- not always collected at booking time, so all three are nullable.
ALTER TABLE booking_seats ADD COLUMN passenger_name VARCHAR(255);
ALTER TABLE booking_seats ADD COLUMN passenger_phone VARCHAR(30);
ALTER TABLE booking_seats ADD COLUMN passenger_id_number VARCHAR(50);

-- Reference for non-cash payments (mobile money/card transaction id).
ALTER TABLE payments ADD COLUMN transaction_id VARCHAR(60);

-- Human-facing ticket identifiers, distinct from the internal UUID id -
-- what actually gets printed/read back to a passenger. subtotal/tax split
-- out what total_amount used to compute as a single flat number.
ALTER TABLE bookings ADD COLUMN ticket_number VARCHAR(40);
ALTER TABLE bookings ADD COLUMN booking_ref VARCHAR(10);
ALTER TABLE bookings ADD COLUMN subtotal_amount NUMERIC(10,2);
ALTER TABLE bookings ADD COLUMN tax_amount NUMERIC(10,2);

-- Backfill: derive a unique ticket_number/booking_ref from the existing id
-- (guaranteed unique since id is) rather than leaving old bookings without
-- one. Treat pre-VAT history as subtotal = what was actually charged,
-- tax = 0 - don't invent a retroactive tax charge for bookings that already
-- completed under the old flat-price rule. Same generation scheme
-- TicketNumberGenerator uses for new bookings, kept in sync deliberately.
UPDATE bookings SET
    ticket_number = 'BK-' || upper(substr(replace(id::text, '-', ''), 1, 12)),
    booking_ref = upper(substr(replace(id::text, '-', ''), 1, 6)),
    subtotal_amount = total_amount,
    tax_amount = 0
WHERE ticket_number IS NULL;

ALTER TABLE bookings ALTER COLUMN ticket_number SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN booking_ref SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN subtotal_amount SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN tax_amount SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN tax_amount SET DEFAULT 0;

CREATE UNIQUE INDEX idx_bookings_ticket_number ON bookings(ticket_number);
CREATE UNIQUE INDEX idx_bookings_booking_ref ON bookings(booking_ref);
