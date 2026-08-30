-- Marketplace search (TripController.search) does, per matching route, a
-- `WHERE route_id = ? AND departure_at > ?` on trips. trips.route_id had
-- no index (idx_trips_departure covers departure_at alone; Postgres does
-- not auto-index FK columns), so every one of those was a scan. This
-- composite index serves the route filter and the departure_at range/sort
-- together.

CREATE INDEX idx_trips_route_departure ON trips(route_id, departure_at);
