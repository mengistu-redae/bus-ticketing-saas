-- Backs "deactivate" for buses/routes (DELETE /api/fleet/{buses,routes}/{id}
-- soft-deactivates rather than deleting the row - see BusController/
-- RouteController). Trips already have a `status` column reused for the
-- same purpose (no migration needed there); operators already have
-- `status` too (reused by PlatformController's operator deactivate).
ALTER TABLE buses ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE routes ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
