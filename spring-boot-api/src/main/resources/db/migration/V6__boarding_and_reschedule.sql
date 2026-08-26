-- Boarding Gate State Machine (my-notes/ethiopian_bus_system_specs.md
-- section 4.1): each seat's passenger gets checked in at the terminal,
-- validated against the ID on file. Trip.status already flips to
-- "boarding_closed" via a plain string value - no migration needed there
-- (see TripLifecycleScheduler), same as how "cancelled" already worked.
ALTER TABLE booking_seats ADD COLUMN boarding_status VARCHAR(20) NOT NULL DEFAULT 'not_boarded';
ALTER TABLE booking_seats ADD COLUMN boarded_at TIMESTAMPTZ;

-- Rescheduling (section 5.3): moves a booking to a different trip/seat
-- rather than cancel-and-rebook. v1 only supports single-seat bookings -
-- see BookingRescheduleService's javadoc for why. A flat mutation fee is
-- layered on top of the new trip's fare (not folded into tax/subtotal, so
-- it stays visible as its own line); booking_reschedules is an audit trail
-- of every reschedule, same role cancellations already plays for cancels.
ALTER TABLE bookings ADD COLUMN reschedule_fee NUMERIC(10,2) NOT NULL DEFAULT 0;

CREATE TABLE booking_reschedules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID NOT NULL REFERENCES bookings(id),
    old_trip_id     UUID NOT NULL REFERENCES trips(id),
    new_trip_id     UUID NOT NULL REFERENCES trips(id),
    old_seat_id     UUID NOT NULL,
    new_seat_id     UUID NOT NULL,
    fee             NUMERIC(10,2) NOT NULL,
    rescheduled_by  UUID REFERENCES app_user(id),
    rescheduled_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_reschedules_booking ON booking_reschedules(booking_id);
