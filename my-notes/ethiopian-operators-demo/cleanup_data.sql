-- Remove all generated demo *data* (fleet / trips / bookings / cargo) for the
-- 12 Ethiopian roster operators, so gen_seed.py's non-idempotent seed can be
-- re-applied. Leaves operators / app_user / operator_settings rows intact.
BEGIN;

CREATE TEMP TABLE _ops ON COMMIT DROP AS
  SELECT id FROM operators WHERE keycloak_org_id IN
    ('sky-bus','golden-bus','abay-bus','habesha-bus','ethio-bus','zemen-bus',
     'liyu-bus','walia-bus','getbus','oda-bus','alsam-bus','yebeza-bus');

DELETE FROM payments WHERE booking_id IN (SELECT id FROM bookings WHERE tenant_id IN (SELECT id FROM _ops))
   OR waybill_id IN (SELECT id FROM cargo_waybills WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM cargo_waybill_cancellations WHERE waybill_id IN (SELECT id FROM cargo_waybills WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM cargo_waybill_items WHERE waybill_id IN (SELECT id FROM cargo_waybills WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM cargo_waybills WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM cargo_rates WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM booking_reschedules WHERE booking_id IN (SELECT id FROM bookings WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM cancellations WHERE booking_id IN (SELECT id FROM bookings WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM bookings WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM seats WHERE trip_id IN (SELECT id FROM trips WHERE tenant_id IN (SELECT id FROM _ops));
DELETE FROM trips WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM refund_policies WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM routes WHERE tenant_id IN (SELECT id FROM _ops);
DELETE FROM buses WHERE tenant_id IN (SELECT id FROM _ops);

COMMIT;
