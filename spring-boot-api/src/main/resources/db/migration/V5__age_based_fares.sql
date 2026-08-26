-- Age-based fare rules from my-notes/ethiopian_bus_system_specs.md section
-- 4.1: age >= 3 is a normal ADULT ticket (full fare, occupies its own
-- seat - no schema change needed, that's every existing booking_seats row
-- already); age < 3 is an INFANT (0.00 fare, seat_constraint LAP_SITTING).
--
-- An infant deliberately does NOT get its own booking_seats/seats row -
-- seats are finite (generated from the bus's capacity), and a lap-sitting
-- infant riding with a paying adult must not reduce the trip's sellable
-- seat count. Instead an infant is recorded as a dependent of the adult's
-- booking_seats row they're riding with - see BookingInfant.

-- The age of the seated (adult) passenger - optional metadata, not itself
-- pricing-relevant since v1 has one flat adult fare regardless of exact age.
ALTER TABLE booking_seats ADD COLUMN passenger_age INT;

CREATE TABLE booking_infants (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id   UUID NOT NULL,
    seat_id      UUID NOT NULL,
    name         VARCHAR(255) NOT NULL,
    age          INT NOT NULL CHECK (age >= 0 AND age < 3),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (booking_id, seat_id) REFERENCES booking_seats(booking_id, seat_id)
);
CREATE INDEX idx_booking_infants_booking ON booking_infants(booking_id);
