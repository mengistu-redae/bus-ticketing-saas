# Intermediate stops (board/alight mid-route) — deferred enhancement

Raised 2026-08-28, deferred to a later enhancement pass. This note captures
the problem and the open design forks so the future session starts warm.

## The requirement

A passenger's (or a shipment's) **boarding/pickup** point may be somewhere
along a trip's route *after* the route origin, and the **alighting/drop-off**
point may be *before* the route's final destination. Today the whole system
assumes every booking and every waybill runs full route-origin →
route-destination.

## Why it's a big change — current model

- `routes` = a single `(origin, destination)` pair (+ optional terminals,
  `distance_km`). Index `idx_routes_search(origin, destination)`.
- `trips` = one `departure_at`, one `arrival_at`, one flat `price`.
- `seats` = generated per trip; `seats.status` is **binary open/booked for
  the whole trip** — this is the availability source of truth.
- `booking_seats.price` = `trip.price` (flat).
- Search (`TripController.search` → `RouteRepository
  .findAllByOriginIgnoreCaseAndDestinationIgnoreCase` +
  `TripRepository.findAllByRouteIdAndDepartureAtAfter`) = exact origin/dest
  match, then trips on that route with `departure_at` in the future and
  `status='scheduled'`.
- `BoardingService.checkIn` gate-lockout compares `Instant.now()` vs
  `trip.departureAt`. `TripLifecycleScheduler` flips `scheduled` →
  `boarding_closed` once past `departure_at`, removing it from search.
- Cargo: `cargo_waybills` references a trip; `cargo_rates.base_freight_charge`
  is route-wide ("origin-destination zones" per BRD).
- `RefundCalculator.calculate(tenantId, routeId, amount, departureAt)` —
  already takes `departureAt` as a param, so refund/reschedule time-gates
  are the *least* affected (just pass the boarding-stop time).

## Components that must change

1. **Route stops** — new `route_stops(route_id, seq, place, terminal,
   distance_from_origin_km)`; origin/destination become the first/last stop.
2. **Trip schedule per stop** — per-stop arrival/departure (new `trip_stops`),
   or offsets on `route_stops` + `trip.departure_at`.
3. **Seat inventory** — the hard part. Binary `seats.status` can't express
   "free for Dessie→Mekelle but sold Addis→Dessie." Either interval-overlap
   availability per requested segment, or keep whole-trip hold and accept the
   lost resale.
4. **Segment pricing** — a fare for any `(fromStop, toStop)` pair.
5. **Search** — match trips where both endpoints are stops on the route in
   the right order; "departure after X" becomes the *boarding stop's* time;
   available-seat count becomes segment-aware. A trip that has already left
   its origin must **stay searchable/bookable for later segments** — so the
   `boarding_closed` flip and the scheduler become per-stop, not per-trip.
6. **Booking** — `CreateBookingRequest` gains board/alight stop; `booking_seats`
   records the segment; price = segment fare; the in-transaction re-check in
   `BookingWriter` becomes an overlap check instead of `status='open'`.
7. **Cargo** — waybill records pickup/drop-off stops; possibly stop-pair /
   zone freight pricing; segment inventory N/A (no seat).
8. **Boarding** — gate lockout vs the *boarding stop's* departure time.
9. **Refund / reschedule** — pass the boarding-stop time as `departureAt`.
10. **Frontend** — route editor gains an ordered stop list; trip editor gains
    per-stop times; seat map shows availability for the chosen segment; search
    result cards show segment board/alight times; fare-config screen if a
    matrix model is chosen.
11. **Data migration** — every route → 2 stops; every trip → 2 trip_stops
    from its existing times; every `booking_seat` → board=first, alight=last;
    fare backfill so the origin→destination segment fare = current
    `trip.price`.

## Open design forks (ask via AskUserQuestion when the pass starts)

1. **Seat inventory**: true segment resale (interval-overlap availability,
   max revenue, replaces `seats.status` model, segment-aware Redis locks) vs
   whole-trip seat hold (simple, seat sold once regardless of segment,
   stops only affect price/boarding/manifest) vs whole-trip-hold-now /
   resale-phase-2 (design the schema so resale layers on with no further
   `booking_seats` migration).
2. **Fare model**: cumulative per-stop price (`fare[alight] − fare[board]`,
   simple, assumes monotonic) vs full `(fromStop, toStop)` fare matrix per
   route (shaped like `refund_policies`/`cargo_rates`, most flexible, new
   CRUD screen) vs distance × per-km rate (minimal config, forces converting
   every existing flat `trips.price`).
3. **Stop times**: explicit per-stop times entered on the trip (`trip_stops`)
   vs duration offsets on `route_stops` + `trip.departure_at`.
4. **Scope**: passenger booking first, cargo pickup/drop-off as a follow-up
   (same way the cargo module was scoped separately) vs passenger + cargo in
   one pass (shared route/stop schema work).

## Rough phasing if/when built

- Phase A: `route_stops` + `trip_stops` schema + migration; route/trip editor
  UI for stops; **no** behaviour change yet (every booking still full-route).
- Phase B: segment-aware search + segment fare + booking board/alight stop +
  boarding gate vs boarding-stop time. Whole-trip seat hold.
- Phase C (optional): true segment resale (overlap-based availability).
- Phase D: cargo pickup/drop-off stops (+ zone freight pricing if wanted).
