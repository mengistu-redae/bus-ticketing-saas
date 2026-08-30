# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A multi-tenant bus ticketing platform: bus operators (tenants) manage their own
fleet, routes and trips; customers search and book across every operator on
the platform; operator staff can also book/cancel on behalf of walk-in
customers at the counter.

```
 browser --> nginx --> node-bff (session, OIDC) --> spring-boot-api (JWT, tenant-aware)
                 \-> keycloak (login only, admin console)      \-> postgres, redis
```

Two services, each with its own build tooling:

- **`spring-boot-api/`** - Java 21 / Spring Boot 3.3, Maven. The tenant-aware
  core API. Stateless, validates bearer JWTs, never talks to the browser
  directly.
- **`node-bff/`** - Node >=20 / Express, npm. The only thing the browser
  talks to. Runs the OAuth2 Authorization Code + PKCE flow against Keycloak,
  holds tokens in a server-side (Redis-backed) session, forwards them to the
  API as a Bearer header. The browser only ever sees a session cookie.
- **keycloak** - identity provider (not app code here, just config in
  `infra/keycloak/`). One realm (`bustix`), four realm roles, and the
  Organizations feature for grouping operator staff by tenant.
- **postgres / redis** - primary datastore and seat-locking / session store.
- **nginx** - single entry point on `:80` for local dev (`infra/nginx/`).

## Commands

Run everything (Postgres, Redis, Keycloak, both apps, nginx):

```bash
docker compose up --build
```

First boot is slow (Keycloak's `start-dev` + realm import); `node-bff`
retries OIDC discovery with backoff for this reason (`discoverWithRetry` in
`node-bff/src/auth/oidc.js`), so services don't need to win a startup race.

One-time setup after first `docker compose up` (see README for full detail):
1. `./infra/keycloak/create-demo-org.sh` - creates the demo operator's
   Keycloak Organization, prints an `INSERT INTO operators (...)` to run
   against `bus_ticketing` (e.g. via `docker compose exec postgres psql -U
   bustix -d bus_ticketing`).
2. Copy the `bus-ticketing-bff` client secret from the Keycloak admin console
   (`http://localhost:8080` -> `bustix` realm -> Clients -> Credentials tab)
   into `.env` as `BFF_CLIENT_SECRET`, then
   `docker compose up -d --force-recreate node-bff`. The
   `changeme-in-keycloak-console` default will not authenticate.
3. Optionally seed a `refund_policies` row for the demo operator so
   cancellations refund something other than zero (SQL in README).

Then log in at `http://localhost:3000/auth/login` as `demo-operator-admin` or
`demo-customer` (both `changeme`, forced to reset on first login).

**spring-boot-api** (from `spring-boot-api/`):
```bash
mvn spring-boot:run     # run locally against localhost Postgres/Redis/Keycloak - no mvnw wrapper is checked into this repo, use system Maven
mvn test                 # run tests
mvn test -Dtest=ClassName#methodName   # run a single test
mvn package               # build the jar
```
Flyway migrations live in `src/main/resources/db/migration/`
(`V1__init.sql`, ...) and run automatically on startup;
`ddl-auto: validate` means schema changes always go through a new migration,
never through Hibernate auto-DDL.

**node-bff** (from `node-bff/`):
```bash
npm install
npm start                   # node src/index.js
npm test                    # node --test (node's built-in test runner, not a package.json dependency)
```

**Running each service individually against local infra** (as opposed to
`docker compose up`, e.g. when Postgres/Redis/Keycloak already run natively
or in standalone containers on the dev machine): each service has a
`start-local.ps1` (PowerShell) that sets the right env vars and starts it -
`spring-boot-api/start-local.ps1`, `node-bff/start-local.ps1` (reads
`BFF_CLIENT_SECRET`/`SESSION_SECRET` out of the repo-root `.env`, since
node-bff has no dotenv dependency and won't read that file on its own), and
`infra/keycloak/start-native.ps1` for a natively-installed Keycloak instead
of the docker-compose `keycloak` service (points it at a Postgres db of your
choosing - see that script's own doc comment for why you might want this).

Service URLs (via docker compose):

| Service | URL |
|---|---|
| App (via nginx) | http://localhost |
| node-bff directly | http://localhost:3000 |
| Keycloak admin console | http://localhost:8080 |
| spring-boot-api (for debugging) | http://localhost:8081 |

## Tenancy model

`tenant_id` on `operators` is the internal tenant key - deliberately not the
same as the Keycloak organization id (`operators.keycloak_org_id`), so the
app isn't hard-wired to Keycloak's id format (see comment atop
`V1__init.sql`).

There is **no** blanket Hibernate multi-tenant filter. Instead:

- `TenantContextFilter` runs once per request (after JWT auth), reads the org
  claim off a staff token, resolves it to an `operators.id` via
  `OperatorRepository.findByKeycloakOrgId`, and stashes it in the
  request-scoped `TenantContext`. **It's also the operator-deactivation
  enforcement point** (added 2026-08-27): if the resolved operator's
  `status != "active"` the filter writes a plain `403 "Operator account is
  deactivated"` and stops the chain - the staff token is locked out of the
  whole API, not just booking creation. Only staff tokens carry an org
  claim, so customer / guest / `platform_admin` requests are untouched (a
  `platform_admin` can still administer a deactivated operator; a customer
  can still search the marketplace). `BookingService.createBooking` keeps
  its own narrower `OperatorInactiveException` check because that's the only
  guard for the customer/guest booking path - those tokens have no org
  claim, so the filter never sees them.
- Every staff-scoped repository method takes the tenant id **explicitly** as
  a parameter (`findByTenantIdAndId(...)`, `findAllByTenantId(...)`) rather
  than a query implicitly reading `TenantContext` deep inside a shared base
  repository - you can tell whether an endpoint is tenant-scoped or
  cross-tenant just by reading its method signature. Write paths that
  reference another resource by id validate it against the caller's tenant
  too (`TripCreationService`'s route/bus, `CargoWaybillService`'s trip/
  booking, `BookingRescheduleService`'s new trip, and - since 2026-08-27 -
  `RefundPolicyController`/`CargoRateController`'s optional `routeId`).
- `TenantContext` is `null` for customer tokens (customers aren't a member of
  any Organization) and for `platform_admin` tokens (acting across every
  tenant). Code that legitimately needs a tenant on the request calls
  `TenantContext.require()`, which throws instead of silently proceeding with
  `null` if wired to the wrong kind of token.
- Every `/api/**` endpoint carries a `@PreAuthorize` (method security is
  enabled - see "Auth / BFF details") except the deliberately-public
  marketplace/guest/track paths in `SecurityConfig`'s `permitAll()` list.

`AppUser.tenant_id` is a **mirror only** - nullable (`NULL` for customers/
platform admins, set for staff), written once at provision time from
`TenantContext`, and **never consulted for authorization**. The per-request
token is the source of truth, so moving a user between Keycloak orgs takes
effect immediately regardless of the stale `app_user.tenant_id`.

`TenantIsolationIntegrationTest` (`com.bustix.tenant`) is the consolidated
proof: for every staff-scoped resource, operator A seeds it and operator
B's agent/admin is refused (404/403) on every read/write/action path -
plus the deactivation lockout and the cargo-request routing below.

**Marketplace exception** - customers browse/book across every operator, so
these read paths are intentionally cross-tenant (no tenant filter):
`RouteRepository.findAllByOriginAndDestination` (backed by
`idx_routes_search`) and `TripRepository.findAllByRouteIdAndDepartureAtAfter`.
Staff-facing endpoints for managing one operator's own routes/buses/trips use
the tenant-scoped finders instead. Both are wired up:
`GET /api/trips/search?origin=...&destination=...&departureAfter=...`
(`CUSTOMER`/`AGENT`, `TripController.search`) is the customer-facing read
path over these; `GET/POST/PATCH/DELETE /api/fleet/{buses,routes,trips}(/{id})`
(`OPERATOR_ADMIN` only, `BusController`/`RouteController`/`TripController`)
is how an operator's own fleet data gets created, listed, read one at a
time, corrected, and retired - full CRUD as of 2026-08-23 (previously only
create + list existed). `PATCH` is a partial update: only non-null fields in
the request are applied, and a trip's `routeId`/`busId` are deliberately not
editable that way, since seats are generated from the bus's capacity/layout
once, at trip creation - changing the bus afterwards would leave existing
seats mismatched with the new one.

`DELETE` on all three **soft-deactivates, never a row delete** - a
bus/route/trip can be referenced by existing seats/bookings, so removing the
row outright would either violate those foreign keys or silently orphan
history. Buses and routes got a new `active BOOLEAN NOT NULL DEFAULT true`
column for this (`V2__fleet_active_flag.sql`) - `PATCH {"active": true}`
reactivates. Trips reuse the `status` column that already existed instead
(`DELETE` sets it to `"cancelled"`; no new migration needed there).
Deliberately **no cascading effect** on either: deactivating a route/trip
doesn't touch its existing bookings (no auto-refund, no notification, and
the marketplace search doesn't yet exclude inactive routes) - that's the
same bigger "trip lifecycle transitions" feature called out below, not
solved by this pass. Creating a trip also generates its `seats` rows from
the bus's `capacity`/`seat_layout` - see `SeatLayoutGenerator` for the "AxB"
layout parsing (`"2x2"` = 4 seats/row: `1A,1B,1C,1D,2A,...`) and its
plain-number fallback for anything that doesn't parse. `GET
/api/trips/{tripId}/seats` (`CUSTOMER`/`AGENT`) is the seat map for one trip
- there was previously no way to discover a seat's id through the API at
all, found while manually verifying customer self-cancel (had to query
Postgres directly for one).

`GET /api/trips/search` also takes `page`/`size` (default `0`/`20`, `size`
capped at 100, added 2026-08-23) - previously returned every matching trip
unbounded. The cap is applied **in-memory** after the full cross-tenant
result set is assembled and sorted, not pushed down into the route-then-trip
loop's DB queries; the response carries an `X-Total-Count` header so a
caller can tell how much was cut off. A real fix would restructure that loop
into one paginated query - bigger change, not done here.

`bustix.tenant.org-claim-path` in `application.yml` names the token claim
`TenantContextFilter` reads for the org id. **Confirmed against a real
token on Keycloak 26.7.1** (decoded via the admin API's password grant, not
guessed): the claim only appears at all if the client's authorization
request explicitly includes the `organization` scope - Keycloak ships it as
an *optional* client scope even with Organizations enabled on the realm, not
a default one, so a client that only asks for `openid profile email` (as
`node-bff` originally did) gets no org claim on any token, ever, regardless
of what `org-claim-path` is set to. Once requested, the claim comes back as
a JSON array of org **aliases** (`"organization": ["demo-bus-co"]`), not an
id and not an object keyed by org id - `TenantContextFilter.extractOrgId`
handles that shape (taking the first element; a user belongs to at most one
org here) alongside the plain-string/id-keyed-map shapes older Keycloak
versions use. Consequently **`operators.keycloak_org_id` must store the org
alias, not Keycloak's internal org UUID** -
`infra/keycloak/create-demo-org.sh` prints the alias for exactly this
reason. If you're on a different Keycloak version, re-verify this shape
(jwt.io or the Admin Console's "Evaluate" tab) rather than assuming it
still holds.

## Booking flow

`BookingService`: select seat(s) -> acquire a short-lived Redis lock per seat
(`SeatLockService`, TTL `bustix.seat-lock.ttl-seconds`) -> lock acquired
(write the booking) or already locked (409 `SeatConflictException`). The lock
only proves *this request* currently holds the seat in Redis;
`BookingWriter`'s DB write re-checks `seats.status = 'open'` inside the
transaction, so a seat sold through some other path can't be double-sold.

The DB write lives in `BookingWriter`, a **separate bean** from
`BookingService`, so its `@Transactional` goes through Spring's proxy
correctly - calling a `@Transactional` method on `this` from inside the same
class silently skips the transaction. `CancellationService` and
`CurrentUserService.provision` follow the same split for the same reason.
Keep this in mind when adding new transactional writes.

Idempotency: a `(tenant_id, idempotency_key)` unique constraint on `bookings`
means a retried request with the same key returns the original booking
rather than re-locking seats or double-booking - checked before any locking.

`channel` is `self_service` (customer BFF, JWT role `customer`, no org) or
`counter` (agent BFF, JWT role `agent`, org set - enforced to match the
trip's own operator via `TenantMismatchException`, which `BookingController`
maps to HTTP 403). That mapping was missing until 2026-08-23 - the
exception's own javadoc claimed 403 but nothing actually did it, so it fell
through to a 500 - found by adding `BookingIntegrationTest`'s
cross-tenant-agent case.

Every request resolves its Keycloak subject to an internal `app_user.id` via
`CurrentUserService.resolveInternalUserId`, provisioning the row on first
login (Keycloak stays the source of truth for identity; `app_user` is a local
mirror joined against `bookings.customer_user_id`,
`cancellations.cancelled_by`, notification recipient lookups, etc.).

**No staff-facing lookup of bookings existed until 2026-08-24** -
`BookingRepository.findAllByTenantId`/`findByIdAndTenantId` had been there
since V1, but nothing in `BookingController` exposed them, so an agent had
no API to see their own operator's bookings at all (a gap found while
scoping the not-yet-built agent/counter frontend). `GET /api/bookings`,
`GET /api/bookings/{bookingId}`, `GET /api/bookings/{bookingId}/seats`
(`AGENT`/`OPERATOR_ADMIN`, tenant-scoped, same `BookingController`) fill
that gap - `bookingSeats`/`myBookingSeats` share one private
`bookedSeatViews(bookingId)` helper for the seat/passenger join, tenant vs.
ownership access-checked separately before calling it.

## Ticketing (passenger/ticket fields)

Added 2026-08-24, modeled directly on a real Selam Bus Line ticket and a
BRD written from several Ethiopian operators
(`my-notes/ethiopian_bus_system_specs.md`, not itself implemented in full -
see "Known gaps" below for what's deliberately still missing from it).
`V3__ticketing_details.sql` and `V4__passenger_identity_type.sql` bring the
schema from "flat price, anonymous seats" to something closer to a real
printed ticket:

- **`POST /api/bookings`'s request shape changed** - `CreateBookingRequest`
  no longer takes a bare `seatIds: [UUID]`; it takes `passengers: [{seatId,
  passengerName, passengerPhone?, passengerIdNumber?, passengerIdType?}]`
  (`CreateBookingRequest.PassengerSeat`). A real ticket is issued to a named
  passenger per seat, not an anonymous seat id - this applies to both
  channels (self_service and counter), not just the counter/agent path that
  prompted it. `passengerPhone` is validated E.164-Ethiopia
  (`^\+251[79]\d{8}$`) via `@Pattern`, skipped when null/blank.
  `passengerIdType` is `IdentityDocumentType`
  (`KEBELE_ID`/`DIGITAL_ID`/`PASSPORT`/`DRIVERS_LICENSE`) - stored alongside
  `passengerIdNumber` on `booking_seats`, both nullable since an ID is
  often checked at the terminal at boarding, not always known at booking
  time. **This was a breaking change** to the customer-facing frontend built
  in Phase 1 (`SeatSelection.jsx` sent the old `seatIds` shape) - fixed the
  same day, see "Frontend"'s "Phase 1.5" note below.
- `bookings` gained `ticket_number` (e.g. `DBC-2026-4763827` - operator-name
  initials + year + a 7-digit number, `TicketNumberGenerator`),
  `booking_ref` (a bare 6-char PNR-style code, same generator), and a
  `subtotal_amount`/`tax_amount` split of what `total_amount` already meant
  - `total_amount` is now `subtotal + tax` rather than a single flat
  number. Both generated columns are checked for uniqueness with a bounded
  retry (`bookingRepository.existsByTicketNumber`/`existsByBookingRef`)
  before being assigned, not trusted from randomness alone.
- **VAT**: `bustix.ticketing.vat-rate` (`application.yml`, default `0.15`,
  **per-operator-overridable since 2026-08-30 - see "Per-operator
  settings"**; `BookingWriter` reads `OperatorSettingsService.resolve(...)
  .vatRate()`, not the `@Value` directly) is applied to a booking's
  subtotal by `BookingWriter` to get `tax_amount`.
  Existing pre-2026-08-24 bookings were backfilled with `tax_amount = 0`
  (not retroactively taxed) and a ticket/PNR derived from their existing
  `id` - see the migration's own comment for why that's a safe one-time
  backfill rather than something `TicketNumberGenerator` needs to special-
  case.
- `routes` gained optional `origin_terminal`/`destination_terminal`
  (`RouteController` create/update); `operators` gained optional `tin`
  (`PlatformController` create/update - safe to edit via `PATCH`, unlike
  `keycloak_org_id`, since it doesn't feed `TenantContextFilter`);
  `payments` gained optional `transaction_id` (`PaymentController`
  create/update). `TripSearchResult` (both `search()` and
  `getTripDetails()`) now also carries `operatorTin`, `originTerminal`/
  `destinationTerminal`, `busPlateNo` (a new `BusRepository` lookup
  alongside the existing `OperatorRepository` one), and a derived
  `reportingAt` (`departureAt` minus `bustix.ticketing.reporting-buffer-
  minutes`, default 30 - not a stored column).
- **A real bug found live while testing this**: any `@Valid` bean-
  validation failure not caught by a controller-local
  `@ExceptionHandler` (e.g. the new `passengerPhone` `@Pattern`) came back
  as a misleading 403 `insufficient_scope` instead of 400. Spring's
  `DefaultHandlerExceptionResolver` writes such failures via
  `response.sendError(...)`, which triggers the servlet container's
  internal forward to `/error` - a *new* request dispatch that re-enters
  `SecurityConfig`'s filter chain. `/error` wasn't in the allow-list, so it
  fell into `anyRequest().denyAll()` and got rewritten into an access-denied
  response, masking whatever status the original exception actually
  resolved to. Fixed by adding `.requestMatchers("/error").permitAll()` -
  this affected every `@Valid`-validated endpoint across the whole API, not
  just this one, and wasn't caught by the existing integration suite
  because none of it happens to assert a validation-failure path yet.

## Age-based fares (infants)

Added 2026-08-25, from `my-notes/ethiopian_bus_system_specs.md` section
4.1: age >= 3 is a normal adult ticket (full fare, occupies its own seat -
every existing booking flow already worked this way, no change needed).
Age < 3 is an infant - free, and per the BRD's own `LAP_SITTING` seat
constraint, does **not** get a seat of its own. `V5__age_based_fares.sql`
models this literally: an infant is never a `passengers` entry / never
gets a `booking_seats`/`seats` row - seats are finite (generated from the
bus's capacity at trip creation), and a lap-sitting infant riding with a
paying adult must not reduce the trip's sellable seat count. Instead a new
`booking_infants` table (composite FK to `booking_seats(booking_id,
seat_id)`) records an infant as a dependent of the adult seated there -
see `BookingInfant`'s javadoc for the full reasoning; this was a deliberate
design choice, confirmed with the user before implementing, not an
assumption.

`CreateBookingRequest.PassengerSeat` gained `age` (optional, descriptive
only - v1's flat adult fare doesn't vary by exact age) and `infants`
(`List<Infant>`, each `age` constrained `0..2` by its own `@Max` - `age`
alone doesn't make something an infant, being listed in `infants` does).
A 5-arg secondary constructor was kept alongside the new 7-arg canonical
one specifically so existing Java call sites (tests) didn't all need
updating again - Jackson still binds JSON via the canonical constructor
regardless. `BookingWriter` persists `booking_infants` rows in the same
per-seat loop as `booking_seats`, after that seat's row exists (the
composite FK requires it). `BookedSeatView` (both the customer
`GET /my-bookings/{id}/seats` and staff `GET /bookings/{id}/seats`) gained
`passengerAge` and a nested `infants: [{name, age}]` list, grouped by
`seatId` in `BookingController.bookedSeatViews`.

Frontend: `PassengerDetailsForm` (shared by both the customer and
agent/counter seat-selection pages) gained an "Age" field and a
repeatable "+ Add infant" sub-section per seat - shown for both flows
(unlike `showIdFields`, which stays agent-only), since any booking can
include a lap-sitting infant. Both `BookingDetail` pages (customer and
staff) render `{name} ({age})` after each seat's passenger name when
`infants` is non-empty.

Verified live end-to-end (not just `mvn test-compile`, though a new
`BookingIntegrationTest` case - `infantRidesFreeOnAnAdultsLapWithout...`
- covers it too, same Testcontainers-unrun caveat as the rest of that
suite): booked a seat with an accompanying infant via curl, confirmed
`subtotalAmount` reflected only the adult fare and the *other* seat on
that row stayed `open`; confirmed a real browser booking through the
fixed customer flow shows "customer demo + Little One (2)" on the
confirmation page; confirmed an infant `age` >= 3 is rejected with a real
400 (also re-confirming the `/error` fix from earlier this session).
Booking cancelled afterward, no leftover state.

## Boarding Gate State Machine

Added 2026-08-25, from `my-notes/ethiopian_bus_system_specs.md` section
4.1's "Validation Engine" and "Gate Lockout" rules. New `com.bustix.boarding`
package (`BoardingService`/`BoardingController`), `V6__boarding_and_reschedule.sql`.

- **Check-in**: `POST /api/bookings/{bookingId}/seats/{seatId}/check-in`
  (`AGENT`/`OPERATOR_ADMIN`, tenant-scoped, `CheckInRequest{presentedIdNumber}`)
  compares the presented ID against `booking_seats.passenger_id_number`
  (`BookingSeat.boardingStatus`/`boardedAt`, both new columns) - a mismatch
  (or nothing on file to check against, since "mandatory ID for boarding"
  is the point) throws `IdentityMismatchException`, mapped to 409.
  Idempotent: re-checking-in an already-boarded seat just returns the
  existing state.
- **Gate Lockout**: `BoardingService.checkIn` compares `Instant.now()`
  against the trip's own `departureAt` *at call time* and throws
  `BoardingClosedException` (409) if departed - this live comparison, not
  a stored status read, is the actual enforcement. A separate
  `TripLifecycleScheduler` (`@Scheduled(fixedDelay = 60_000)`, same shape
  as `NotificationWorker`'s outbox poller) bulk-flips `trips.status` from
  `scheduled` to `boarding_closed` once departed, purely so a departed
  trip disappears from marketplace search (which already filters on
  `status='scheduled'`) - a polling job's lag must never be the thing
  deciding whether a real-time gate check-in is allowed, which is why
  `checkIn` never reads that status back.
- Frontend: agent `BookingDetail.jsx` gained a per-seat check-in row (an
  ID input + "Check in" button, replaced by a "Boarded {time}" pill once
  boarded) - customer-facing pages don't get this, since a passenger
  doesn't check themselves in.

Verified live: curl (mismatched ID → 409, correct ID → `boardingStatus:
"boarded"`, `GET .../seats` reflecting it) and a real browser as
`demo-agent` (wrong ID showing the mismatch error inline, correct ID
flipping to the "Boarded" pill) - both against a fresh booking, cancelled
afterward. A `BoardingIntegrationTest` covers the same three cases (match/
mismatch/departed-trip), same Testcontainers-unrun caveat as the rest of
the suite.

## Rescheduling

Added 2026-08-25, from `my-notes/ethiopian_bus_system_specs.md` section
5.3. **v1 only supports single-seat bookings** - which new seat maps to
which existing passenger, possibly on a different new trip, is genuinely
ambiguous for a multi-seat booking without an explicit per-seat mapping
the BRD doesn't specify; confirmed as the intended scope with the user
before building, not assumed. `BookingRescheduleService` mirrors
`CancellationService`'s shape (one `@Transactional` method per access
path, both delegating to a shared private `apply*`).

- `POST /api/bookings/{bookingId}/reschedule` (staff, tenant-scoped) and
  `POST /api/my-bookings/{bookingId}/reschedule` (customer, ownership-
  scoped) - `RescheduleBookingRequest{newTripId, newSeatId}`. Per the
  BRD's "Immutability Principle," passenger name/phone/ID/age carry over
  from the old `booking_seats` row unchanged; only the trip and seat move.
  `boardingStatus`/`boardedAt` are deliberately **not** carried over - the
  passenger hasn't boarded the new trip.
- **Time Gate**: blocked with `TooLateToRescheduleException` (409) if
  fewer than `bustix.ticketing.reschedule.min-notice-hours` (12) remain
  before the *current* trip's departure - the BRD's own language ("route
  the request to the refund engine") means the caller falls back to
  cancellation, not that this endpoint does it for them.
- **Platform Fee Engine**: a flat mutation fee
  (`bustix.ticketing.reschedule.fee-self-service` = 50.00,
  `...fee-counter` = 100.00 - the BRD names a 50-100 ETB range without
  pinning per-channel amounts, so this session mapped agent-assisted to
  the higher end) is added on top of the *new* trip's subtotal+VAT, not
  folded into either - `bookings.reschedule_fee` is a new column holding
  the most recent fee. `booking_reschedules` is an audit trail (old/new
  trip+seat, fee, who, when), the same role `cancellations` already plays
  for cancels.
- The seat move itself: insert the new `booking_seats` row first, then
  repoint any `booking_infants` at it, *then* delete the old row and free
  its seat - order matters because `booking_infants` has a composite FK to
  `booking_seats(booking_id, seat_id)`, which must stay satisfiable at
  every step, not just at the end. Reuses `SeatLockService` around the new
  seat exactly like `BookingWriter` does at creation time.
- A new trip must belong to the *same operator* as the existing booking
  (`TenantMismatchException`, reused from the booking-creation path) - a
  booking is one operator's inventory; moving it to a different operator's
  trip is a new booking, not a reschedule of the same ticket.
- Frontend: a new `Reschedule` page per role (`/bookings/{id}/reschedule`,
  `/agent/bookings/{id}/reschedule`) - candidate trips are fetched via the
  same `useTripSearch` hook the customer Home/agent Search pages use,
  pre-filled with the current trip's own origin/destination and excluding
  the current trip, then the chosen trip's seat map (`SeatMap`, reused
  as-is) picks the new seat. Both `BookingDetail` pages show a
  "Reschedule" link only when the booking has exactly one seat.

Verified live end-to-end: curl (a successful reschedule with the new
trip's fare + VAT + counter fee; a <12h-notice booking correctly 409ing;
a multi-seat booking correctly 409ing) and a real browser as
`demo-customer` (searched → picked a new trip → picked a new seat →
confirmed - ticket number/PNR unchanged, new departure/seat/total shown,
50.00 self-service fee included) - cancelled afterward, no leftover
state. `BookingRescheduleIntegrationTest` covers the same three cases,
same Testcontainers-unrun caveat as the rest of the suite.

## Cargo & Logistics Module

Added 2026-08-25, from `my-notes/ethiopian_bus_system_specs.md` section 3
("Cargo & Logistics Module") and section 4.2 ("Prohibited Items") - the
last unbuilt piece of that BRD (see "Known gaps" below for the parts of it
still not built, and `my-notes/cargo_logistics_scope_v1.md` for the full
scoping session this was built from, including every design fork
confirmed with the user before implementing - read that doc, not just
this summary, before extending the module). New `com.bustix.cargo`
package, `V7__cargo_logistics.sql`.

- **Waybills are staff-only** (`AGENT`/`OPERATOR_ADMIN`, tenant-scoped) -
  a parcel is physically weighed/inspected at a terminal counter, unlike a
  passenger booking there's no customer self-service creation path in v1.
  `POST /api/cargo/waybills` (`CreateWaybillRequest`: `tripId` required
  (mirrors `bookings.tripId`), optional `bookingId` (accompanied excess
  baggage on an existing passenger booking - must be on the *same* trip,
  enforced via `BookingTripMismatchException`), consignor/consignee
  name+phone (E.164 Ethiopian, same `@Pattern` as passenger phones) +
  optional consignor ID / **required** consignee ID (unlike a passenger's
  optional `passengerIdNumber` - the collect-time check below has nothing
  to verify against otherwise), `description`, `quantity`, optional
  `declaredValue`, and `grossWeightKg`.
- **Status is a manual staff-driven state machine**, not time/scheduler-
  inferred: `issued` → `dispatched` → `arrived` → `collected` (or
  `cancelled`, pre-dispatch only), each its own endpoint
  (`POST .../dispatch|arrive|collect`) mirroring `BoardingService`'s
  shape - re-calling a transition already reached is idempotent (returns
  current state), calling one out of order throws
  `InvalidWaybillStatusException` (409). `collect`
  (`CollectWaybillRequest{presentedIdNumber}`) checks the presented ID
  against `consignee_id_number` on file, same pattern as
  `BoardingService.checkIn` against `passenger_id_number` -
  `ConsigneeIdentityMismatchException` (409) on mismatch, kept as its own
  class rather than reusing boarding's `IdentityMismatchException` since a
  pickup and a boarding are distinct concepts even though the check is
  shaped the same.
- **Freight pricing** (`cargo_rates`) is shaped exactly like
  `refund_policies` - `route_id` nullable (`NULL` = operator-wide
  default, a route-specific row overrides it), configured via
  `GET/POST/PATCH/DELETE /api/fleet/cargo-rates(/{id})`
  (`OPERATOR_ADMIN`), same real-delete-not-soft-deactivate reasoning as
  `RefundPolicyController`. Unlike `RefundCalculator`'s "no policy = 0%
  refund" fallback, **a missing rate blocks waybill creation** (400
  `NoCargoRateConfiguredException`) rather than defaulting to a free
  shipment - a missing refund policy is safe to default to zero, a missing
  freight charge isn't. `free_weight_threshold_kg` (BRD default `30.00`)
  lives on this same row too, not hardcoded - added after the user
  explicitly asked for it to be operator-configurable rather than a fixed
  constant. `excess_weight_kg`/`base_freight_charge`/`weight_surcharge`/
  `handling_service_fee`/`total_cargo_cost` are all computed once (at
  create, or at a pre-dispatch weight correction) and **snapshotted** onto
  the waybill row, same principle as `trips.price` being copied onto a
  booking - a later change to an operator's rate config never
  retroactively re-prices an already-issued waybill.
- **Prohibited items** (section 4.2's blacklist) is a platform-wide config
  list, `bustix.cargo.prohibited-items` in `application.yml`, checked
  against `description` at creation and on any `PATCH` that changes it
  (`ProhibitedItemsChecker`, `ProhibitedItemException` → 400). Each entry
  compiles as a case-insensitive regex (falls back to a literal-substring
  match if the entry isn't valid regex syntax), so "regex-supported"
  doesn't force every configured term to actually be one. Bound via a new
  `CargoProperties`/`@ConfigurationProperties` class, **not** `@Value` -
  a real YAML sequence has no single property at the bare
  `bustix.cargo.prohibited-items` key (Boot stores it as indexed
  `prohibited-items[0]`, `[1]`, ...), so a plain
  `@Value("${bustix.cargo.prohibited-items}")` does not reliably resolve
  it. Worth remembering for any future list-shaped config value anywhere
  else in this app.
- **"Immutability Principle" carried over from passenger tickets**: every
  physical-shipment field (`description`, `quantity`, `declaredValue`,
  `grossWeightKg`, both parties' name/phone/ID) is only `PATCH`-editable
  while `status = "issued"` - once `dispatched`, editing any of them
  throws `InvalidWaybillStatusException` (409) rather than silently no-
  op'ing. `paymentStatus` (`unpaid`/`paid`/`collect_on_delivery`, a plain
  string column, not wired into the existing `payments` table) is exempt
  from that freeze and editable at any status.
- **Cancellation reuses `RefundCalculator` as-is** - it was already
  generic (`calculate(tenantId, routeId, totalAmount, departureAt)`, not
  booking-specific), so `POST /api/cargo/waybills/{id}/cancel`
  (pre-dispatch only - post-dispatch cargo is physically on a moving bus)
  resolves a refund off the waybill's trip and total cost against the
  **same `refund_policies`** an operator already configures for passenger
  bookings, no new refund mechanism. `cargo_waybill_cancellations` is a
  parallel audit trail to `cancellations`, not a reuse of that
  booking-shaped table.
- **One public, unauthenticated endpoint**:
  `GET /api/cargo/track/{waybillNumber}?phone=...` - a consignor/consignee
  is often not a registered platform customer at all, so the existing
  ownership-scoped (`findByIdAndCustomerUserId`) pattern doesn't apply.
  Two-factor instead: `phone` must match either party's phone on that
  waybill, any mismatch (or unknown waybill number) 404s identically, same
  "exists but not yours reads as doesn't exist" rule the rest of the API
  follows. Required adding `/api/cargo/track/**` to `SecurityConfig`'s
  `permitAll()` list **ahead of** the blanket `/api/**` `.authenticated()`
  rule (matchers apply in order). The response
  (`WaybillTrackingView`) is deliberately narrow - status, timestamps,
  route/departure only, never money, ID numbers, or names - since this
  path has no session and no tenant check beyond the phone match.

Verified live end-to-end via curl against the real dev stack (not just
`mvn test-compile`): `V7` applied cleanly against the existing seeded
database and Hibernate's schema validation (`ddl-auto: validate`) passed
on startup. Exercised as `demo-agent`/`demo-operator-admin`: an
operator-wide cargo rate created, a waybill issued and priced correctly
(45kg → 15kg excess → 150.00 surcharge → 400.00 total), a prohibited-item
description correctly 400ing, full lifecycle through `collected`
(including a wrong-ID 409 at collect and an idempotent re-collect),
`PATCH` after dispatch correctly 409ing on a physical field but still
accepting `paymentStatus`, a second waybill cancelled pre-dispatch with
the seeded refund policy correctly computing 0% (under the 2h-notice
tier), a temporarily-deleted rate correctly 400ing waybill creation before
being recreated, and the public track endpoint working with zero
`Authorization` header and 404ing identically on a wrong phone or unknown
number.

**Frontend added 2026-08-25, same session** (plan-mode approved first) -
`pages/cargo/Waybills.jsx` (list + create form) and
`pages/cargo/WaybillDetail.jsx` (lifecycle actions, pre-dispatch edit,
always-editable payment status), both under a single shared `/cargo`
route tree reachable by AGENT and OPERATOR_ADMIN alike - unlike every
other staff area in this app, cargo endpoints have identical permissions
for both roles, so `RequireRole` gained a `roles` (array) prop alongside
its original single-`role` one rather than duplicating pages per role.
`pages/operator/CargoRates.jsx` (operator_admin-only) mirrors
`RefundPolicies.jsx`'s list-plus-inline-edit shape. `pages/Track.jsx` is
the one page in the whole app reachable with **no login at all**.

Two real backend gaps surfaced while wiring this up, both fixed the same
session:
- `GET /api/fleet/trips` and `GET /api/fleet/routes` (list endpoints only
  - not get/create/update/delete) were `OPERATOR_ADMIN`-only, but the
  cargo trip-picker needs both roles to resolve a trip's route name -
  widened to `hasAnyRole('OPERATOR_ADMIN', 'AGENT')` on those two list
  methods specifically (`TripController.list`/`RouteController.list`),
  since both are already tenant-scoped via `TenantContext.require()` and
  an agent seeing their own operator's trips/routes isn't a new
  capability, just a natural extension of "agent can already search/book
  trips."
- `node-bff/src/routes/api.js`'s `buildApiRouter` gated *every* `/api/*`
  path behind `requireSession`, which would 401 an anonymous visitor to
  `/track` before the request ever reached spring-boot-api's own
  `permitAll()` on the tracking endpoint. Fixed by mounting
  `GET /api/cargo/track/*` ahead of that session gate, and by making
  `forwardToApi` only attach a Bearer header when a session actually
  exists (the public path has none) - see that file's own comments for
  the exact ordering, since `authorizeHttpRequests`/Express route
  matching both apply in registration order.

Verified live in a real Chrome browser (not just curl) as both
`demo-agent` and `demo-operator-admin`: full waybill lifecycle
(create → dispatch → arrive → collect, wrong-ID-at-collect error shown
inline, idempotent-looking terminal state), pre-dispatch edit working and
correctly disappearing post-dispatch, payment status editable throughout,
a second waybill cancelled pre-dispatch with the refund amount shown,
`operator_admin` reaching the same `/cargo` pages (proving the multi-role
`RequireRole`), full cargo-rate CRUD at `/operator/cargo-rates` - and,
logged all the way out, `/track` resolving a real waybill with zero
`Authorization` header and cleanly 404ing on a wrong phone number.

## Guest booking & customer shipment history

Added 2026-08-26. Two previously-separate gaps closed in one session: (1)
booking a trip required a logged-in `customer`/`agent` JWT end to end, with
no guest/no-account path at all, unlike cargo tracking which was already
public; (2) a registered customer had no way to see shipments (cargo
waybills) tied to their own bookings - `GET /api/my-bookings` existed, but
nothing equivalent existed for waybills.

- **Guest booking**: `POST /api/bookings/guest` (`CreateGuestBookingRequest`
  - same `tripId`/`passengers`/`idempotencyKey` shape as the authenticated
  `POST /api/bookings`, plus a required `contactPhone` (E.164 Ethiopian,
  same `@Pattern` as everywhere else) and optional `contactEmail`) - fully
  `permitAll()`'d, no `@PreAuthorize`, no `Jwt` parameter at all, mirroring
  `CargoWaybillController.track`'s already-public shape. `channel = "guest"`
  is a new value alongside `self_service`/`counter` (no migration needed,
  `channel` has no CHECK constraint); `customerUserId`/`agentUserId` are
  both `null` (`Booking.customerUserId` was already nullable since V1).
  `V8__guest_bookings.sql` adds the one new durable field a guest booking
  needs: `bookings.guest_contact_phone` (nullable, set only for
  `channel = "guest"`) - without it there'd be nothing to verify a guest's
  identity against on a later lookup. `contactEmail` is **not** persisted -
  it only ever flows through as `BookingWriter`'s existing transient
  `recipientEmail` parameter for the outbox confirmation notification, same
  as an authenticated booking's `recipientEmail` (pulled from the JWT's
  `email` claim there instead). A real bug caught before it shipped:
  `notifications.recipient` is `NOT NULL`, so a guest who leaves
  `contactEmail` blank would have crashed the whole booking write -
  `BookingWriter` now skips the notification write entirely when
  `recipientEmail` is null/blank rather than trying to insert one.
- **Guest booking lookup**: `GET /api/bookings/guest/track/{bookingRef}?phone=`
  - same two-factor (ref + phone) public pattern as
  `GET /api/cargo/track/{waybillNumber}?phone=`, implemented in
  `BookingService.trackByRefAndPhone`. A match is accepted against either
  `bookings.guest_contact_phone` **or** any passenger's own
  `booking_seats.passenger_phone` on that booking - not restricted to
  `channel = "guest"` bookings, so a logged-out customer can use it too. A
  mismatch or unknown ref 404s identically to a wrong one, same "exists but
  not yours reads as doesn't exist" convention as cargo tracking. Response
  is `BookingTrackingView`, deliberately narrow like `WaybillTrackingView`
  (no ID numbers) - ref, ticket number, status, route/departure, seat
  numbers + passenger names, and the fare breakdown. **v1 scope is
  creation + tracking only** - no guest self-cancel/reschedule; those stay
  customer-login-only, unchanged. Staff can still cancel/reschedule a guest
  booking via the existing tenant-scoped `/api/bookings/{id}/cancel`
  /`/reschedule` endpoints, same as any other booking.
- **A second real bug found live while verifying this**:
  `CancellationService`/`BookingRescheduleService` both looked up the
  booking's customer via `appUserRepository.findById(booking.getCustomerUserId())`
  to resolve a cancellation/reschedule notification's recipient - `findById(null)`
  throws `InvalidDataAccessApiUsageException` rather than returning empty,
  so staff-cancelling or -rescheduling a guest booking (`customerUserId
  = null`) 500'd outright. Both now skip that lookup entirely when
  `customerUserId` is null (a guest has no email on file to notify anyway,
  same reasoning as the `BookingWriter` fix above) - found and fixed the
  same session by actually staff-cancelling a live guest booking through
  the agent token, not just creating one.
- **Public read surface widened to match**: `TripController.search`,
  `.locations` (the search-box autocomplete), `.seats` (the seat map), and
  `.getTripDetails` (single-trip lookup) all lost their
  `@PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT')")` - a guest has to be
  able to search/browse/pick a seat before they can book at all, and none
  of these methods branch on role/JWT internally (marketplace search was
  already deliberately cross-tenant). `SecurityConfig` gained matching
  `permitAll()` matchers for all of these plus the two new booking
  endpoints, all ahead of the blanket `/api/**` `.authenticated()` rule,
  same ordering-matters pattern as the existing cargo-track matcher.
  `node-bff/src/routes/api.js`'s single hardcoded cargo-track carve-out
  became a small `PUBLIC_ROUTES` table mounted the same way (ahead of
  `requireSession`/`refreshIfExpired`) covering all of the above - literal
  paths (`/trips/search`, `/trips/locations`) listed before the
  `/trips/:tripId` param route so Express's registration-order matching
  doesn't let the param route swallow them.
- **Customer shipment history**: `GET /api/my-shipments`,
  `GET /api/my-shipments/{waybillId}` (`CUSTOMER`, new methods on the
  existing `CargoWaybillController`) - the natural companion to
  `GET /api/my-bookings`. Scoped through `CargoWaybillRepository`'s two new
  `@Query`-based finders (`findAllByBookingCustomerUserId`/
  `findByIdAndBookingCustomerUserId`) - a JPQL subquery against
  `com.bustix.booking.Booking`, since `CargoWaybill.bookingId` is a scalar
  UUID column, not a mapped JPA relation. A waybill with no `bookingId`
  (the normal case - waybills are still staff-issued only, no
  customer-initiated creation) never shows up here for anyone, by design -
  matching-by-phone against the customer's own profile was considered and
  explicitly rejected (confirmed with the user): `app_user` has no `phone`
  column at all, and booking-ownership is the same access-check shape
  already used everywhere else in this app.
- Frontend: `pages/TrackBooking.jsx` (public, structural copy of the
  existing `Track.jsx`) at `/track-booking`, linked from `PublicShell`
  alongside "Track a shipment". `/search`, `/trips/:tripId`
  (`SeatSelection`), and `/bookings/:bookingId` (`BookingDetail`) all lost
  their `RequireRole role="customer"` wrapper in `App.jsx` - reachable
  logged-out now. `SeatSelection.jsx` renders one extra "Your contact info"
  block (phone required, email optional) only when `!authenticated`, and
  calls the new `useCreateGuestBooking()` instead of `useCreateBooking()`
  in that case - `PassengerDetailsForm` itself needed no change, already
  auth-agnostic. `BookingDetail.jsx` only calls the ownership-scoped
  `useMyBooking`/`useMyBookingSeats` hooks when `authenticated` (passing
  `undefined` otherwise, the same disabled-query trick `tripQuery` already
  used) so a guest never trips `apiFetch`'s blanket 401-redirect-to-login;
  an unauthenticated visit renders from `location.state` only (populated
  right after booking) and points to `/track-booking` for anything else,
  since a guest booking has no other recovery path by design. New
  `pages/customer/MyShipments.jsx` (list) and `MyShipmentDetail.jsx`
  (read-only detail - reuses `WaybillDetail.jsx`'s field layout minus
  every staff-only lifecycle/edit/payment action), both `RequireRole
  role="customer"`, linked from `AppShell`'s customer nav section next to
  "My Bookings".

Verified live end-to-end against the real dev stack (not just
`mvn test-compile`/`vite build`, though both passed): curl - anonymous
search/seats/booking creation with zero `Authorization` header, guest
track by ref+phone succeeding on a match and 404ing cleanly on a wrong
phone, an authenticated customer's existing booking flow byte-for-byte
unchanged, `GET /api/my-shipments` showing a booking-attached waybill and
correctly omitting a standalone one. A real browser, logged all the way
out: searched → picked a seat → filled the guest contact block → booked →
confirmation page showed the PNR with no login prompt anywhere → opened
`/track-booking` in a fresh page load (no `location.state`) → looked the
same booking up successfully by ref + phone, then confirmed a wrong phone
404s with a clean message instead of a crash. Logged in as
`demo-customer`: `/my-shipments` and its detail page both rendering
correctly, `/my-bookings` and an existing booking's Cancel/Reschedule
actions unchanged. All test bookings/waybills cancelled afterward, no
leftover state (the pre-existing operator-wide cargo rate used for this
was left untouched; a duplicate one created by mistake mid-session was
deleted again the same session).

## Operator status enforcement

`operators.status` (set by `PlatformController.deactivate`) is enforced in
**two layers**:

- **Staff API lockout (`TenantContextFilter`, added 2026-08-27)** - a
  deactivated operator's `operator_admin`/`agent` tokens get a flat
  `403 "Operator account is deactivated"` on *every* `/api/**` call, before
  any controller runs. See the Tenancy model section above.
- **Booking-time (`BookingService.createBooking`, added 2026-08-26)** - the
  single method both `POST /api/bookings` and `POST /api/bookings/guest`
  funnel through looks up the trip's `Operator` (`trip.getTenantId()` is
  directly the operator id, no join) right after resolving the trip, before
  the idempotency check or any Redis seat-lock, and throws
  `OperatorInactiveException` (409, `BookingController`) if not `"active"`.
  This layer stays because it's the only guard for the **customer/guest**
  booking path - those tokens carry no org claim, so the filter never sees
  them.

Deliberately **not** enforced on `TripController.search` (the marketplace) -
a deactivated operator's trips still appear in search, so a customer can
pick a seat and only hit the 409 at the final booking step. That residual
is intentional (marketplace visibility ≠ ability to transact).

**Reactivation is still direct-SQL only** - `PlatformController` has no
reactivate endpoint (`status` isn't in `UpdateOperatorRequest`); this
matters more now that deactivation is a hard lockout. Worth a follow-up.

Verified live (both dates): deactivated the demo operator, confirmed a
guest/counter booking 409s with the seat never locked and (2026-08-27) that
`demo-agent`/`demo-operator-admin` get 403 on dashboards/fleet/cargo while
`demo-customer` search and `demo-platform-admin` operator management still
work; reactivated, confirmed access returns.

## Cargo module: multi-item waybills, payments ledger, customer-initiated requests

Added 2026-08-26, closing the three gaps `my-notes/cargo_logistics_scope_v1.md`
had explicitly deferred from the original Cargo & Logistics Module session
(see that section above). All three were scoped via `AskUserQuestion`
before implementing, same as every other design fork in this app's history.

**Multi-item waybills** (`V9__cargo_waybill_line_items.sql`): a new
`cargo_waybill_items` table - each row its own `description`/`quantity`/
`declaredValue`/`grossWeightKg` - replaces the old flat fields directly on
`cargo_waybills`. `description` survives on the waybill itself only as an
optional shipment-level summary (nullable now); `quantity` is dropped
entirely (not meaningful summed across heterogeneous items);
`grossWeightKg`/`declaredValue`/every pricing column stay in shape but are
now snapshotted **sums** of the item rows at write time, computed by a new
`CargoWaybillItem`/`CargoWaybillItemRepository` pair - the pricing formula
in `CargoWaybillService.calculatePricing` itself is unchanged, it's just
fed the item-sum instead of one flat request field. `CreateWaybillRequest`/
`UpdateWaybillRequest` take an `items: List<ItemRequest>` (`@NotEmpty` on
create; nullable-and-replace-the-whole-set on update, since PATCH null
means "don't touch" and an *explicit* empty list is rejected via a new
`InvalidWaybillItemsException`, 400). Existing waybills were backfilled
one item row each from their old flat columns, in the same migration,
before those columns were dropped/relaxed. Since `CargoWaybill` carries no
JPA relation to its items (this codebase never maps cross-entity
relations, always plain UUID FKs + explicit repository queries), every
`CargoWaybillController` read/write endpoint - including
`GET /api/my-shipments(/{id})` - now returns a new `WaybillWithItems`
wrapper (`{waybill, items}`) instead of a bare `CargoWaybill`, the same
purpose-built-read-shape role `WaybillTrackingView` already played for the
public track endpoint. Frontend: a new shared
`components/WaybillItemsEditor.jsx` (add/remove-item-rows), used by both
`pages/cargo/Waybills.jsx`'s create form and `WaybillDetail.jsx`'s
pre-dispatch edit form.

**Payments ledger** (`V10__cargo_payments.sql`): reuses the real
`payments` table instead of a parallel one - a new nullable
`payments.waybill_id`, `booking_id` relaxed to nullable, and a
`chk_payments_exactly_one_owner` CHECK (`(booking_id IS NOT NULL) <>
(waybill_id IS NOT NULL)`) enforced at the DB level, not just application
code. New `com.bustix.cargo.CargoPaymentController` at
`/api/cargo/waybills/{waybillId}/payments(/{id})` - a **separate**
controller from `PaymentController`, since that one's `@RequestMapping`
base path is hard-coded to `/api/bookings/{bookingId}/payments`, a
different resource nesting - reusing the existing `CreatePaymentRequest`/
`UpdatePaymentRequest`/`Payment` entity as-is, same `requireOwnedX` →
`findOwnedPayment` resolution shape as every other tenant-scoped-through-
its-parent controller in this app. No `DELETE`, same "a payment is a
financial fact, not soft-deletable" rule `PaymentController` already
follows. Frontend: `WaybillDetail.jsx` gained a Payments section copied
directly from `pages/agent/BookingDetail.jsx`'s existing "collected of
total" pattern.

**Customer-initiated requests** (`V11__customer_cargo_requests.sql`): a
two-phase flow, since a shipment has to be physically weighed at a
counter - a customer can't issue a fully-priced waybill themselves.
`POST /api/my-shipments` (`CUSTOMER`, `CreateShipmentRequest` - no
`tripId`, no pricing) creates a waybill with `status="requested"` and
`tripId` null. **As of 2026-08-27 the customer routes the request to one
operator up front** (`operatorId`, required - the shipment-request form has
an operator picker fed by the new `GET /api/operators`), so `tenantId` is
set from creation and the waybill only ever appears in *that* operator's
inbox. Staff review it via `GET /api/cargo/requests`
(`findAllByTenantIdAndStatus(tenantId, "requested")` - tenant-scoped like
every other staff endpoint now; the old `...TenantIdIsNull` finder and its
"visible to any operator" caveat are gone) and turn one into a normal
issued waybill via `POST /api/cargo/waybills/{id}/confirm-and-issue`
(`ConfirmAndIssueWaybillRequest` - `findByIdAndTenantId`-scoped like the
rest; assigns the real trip; optionally overrides the consignee ID or
re-weighs the items after physically inspecting the shipment; runs the same
unchanged `calculatePricing`; flips `requested` → `issued`). Idempotent past `requested` - same convention as
`dispatch`/`arrive`/`collect` - 409s via a new `RequestNotIssuableException`
on anything out of order or still missing a consignee ID from both sides.
`GET /api/my-shipments` now unions two ownership paths, not mutually
exclusive: waybills attached to a booking the customer owns, and waybills
they requested directly (`CargoWaybillRepository.findAllOwnedByCustomer`).

**The one real convention-break in this whole session, done deliberately**:
`CargoWaybill` no longer extends `BaseTenantEntity` - it declares its own
`id`/`tenantId`/`createdAt` fields directly instead, same precedent as
`AppUser` (nullable `tenantId`, doesn't extend it either). This was
originally because a `requested` waybill had no operator yet; **as of
2026-08-27 a request is routed to an operator at creation** (see above), so
`tenant_id` is in practice always populated now - but the entity keeps its
declared-locally / nullable shape rather than churning back to
`BaseTenantEntity` + a `NOT NULL` migration on a column `V11` just made
nullable. Every existing derived-query repository method
(`findByIdAndTenantId`, `findAllByTenantId`, etc.) works unchanged - those
are just method names against a `tenantId` property that still exists.

Frontend for the request flow: new `pages/customer/RequestShipment.jsx`
(reuses `WaybillItemsEditor`, no trip picker - the customer may not know
their bus yet), a "Request a shipment" entry point on
`pages/customer/MyShipments.jsx` (previously had no create affordance at
all), a "Pending staff review" banner on `MyShipmentDetail.jsx`. Staff
`pages/cargo/Waybills.jsx` gained a pending-requests section; `WaybillDetail.jsx`
swaps its normal action UI (dispatch/arrive/collect/cancel/payment-status)
for a "Confirm and Issue" form (trip picker + consignee ID + a pre-filled
items editor, auto-populated the first time a `requested` waybill loads
via a one-time `useEffect`, the same lazy-init role `startEdit()` plays
for the existing flat edit form) whenever `status === "requested"`.

New test coverage across all three pieces - this module had **zero**
automated tests before this session, only live/curl verification (see the
original "Cargo & Logistics Module" section above): `CargoWaybillIntegrationTest`
(multi-item create/price, empty-items 400, item-replace PATCH pre/post-
dispatch), `CargoPaymentIntegrationTest` (cross-tenant 404, CHECK-constraint
violation via a raw JDBC insert), `CustomerCargoRequestIntegrationTest`
(request → confirm-and-issue round trip, role checks, idempotent
re-confirm, missing-consignee-ID 409, cross-customer 404) - all compile,
same Testcontainers-unrun-on-this-machine caveat as the rest of the suite
(see "Known gaps" below).

Verified live end-to-end against the real dev stack for all three pieces,
not just `mvn compile`/`npm run build` (though both were clean throughout):
`V9`/`V10`/`V11` each applied cleanly with every pre-existing waybill/payment
row unaffected (backfilled correctly for `V9`, simply widened for `V10`/`V11`);
a real 2-item waybill priced correctly by hand-computed aggregate weight;
an item-replace PATCH re-pricing correctly and freezing post-dispatch
while `paymentStatus` stayed exempt; two real payments (cash + telebirr
with a txn id) recorded and listed against a waybill; a real `demo-customer`
shipment request with no trip, showing up in `demo-agent`'s pending-requests
inbox, correctly 409ing with no consignee ID then succeeding with one plus
re-weighed items landing *exactly* on the free-weight threshold (0
surcharge, confirming that boundary case specifically); an idempotent
re-confirm; the inbox correctly emptying once the tenant was set; the full
post-issue lifecycle completing normally with items intact throughout. No
browser-based frontend verification was done this session for any of the
three pieces - only `npm run build`, never exercised live in Chrome; worth
doing before fully trusting the new UI (the items editor, payments
section, request form, pending-requests inbox, confirm-and-issue form).

## Dashboards

Added 2026-08-27. Before this, every authenticated user landed on `/` =
`pages/customer/Home.jsx` (the marketplace search box) - an operator_admin/
agent/platform_admin saw a customer trip-search form with no overview.
There were **no aggregate/stats endpoints** at all; every repository method
was a single-entity lookup or a full unfiltered list.

New `com.bustix.dashboard` package (`DashboardController`/`DashboardService`
+ record response shapes), one read-only endpoint per role, each gated by
`@PreAuthorize` and scoped exactly like the rest of the API:

- `GET /api/operator/dashboard?period=7d|30d|90d` (`OPERATOR_ADMIN`,
  tenant-scoped via `TenantContext.require()`)
- `GET /api/agent/dashboard` (`AGENT`, tenant-scoped; fixed 14-day window,
  no period param)
- `GET /api/platform/dashboard?period=` (`PLATFORM_ADMIN`, **cross-tenant,
  never touches `TenantContext`**)
- `GET /api/my-dashboard` (`CUSTOMER`, ownership-scoped via
  `currentUserService.resolveInternalUserId`)

**No `SecurityConfig` or `node-bff` change** - the blanket `/api/**`
`.authenticated()` rule already covers these, and `forwardToApi` passes the
`period` query param straight through. New repository query methods were
added to the existing domain repositories (Booking/Trip/Cargo/Bus/Route/
Operator/Payment), each tenant-scoped one taking `tenantId` explicitly, same
"scope is visible in the method signature" convention as everything else.

**Analytics (operator + platform get the full treatment; v2, same day):**
period-over-period deltas (`{current, previous, deltaPct}`), a gap-filled
daily `series` (bookings/revenue/cancellations), categorical `breakdowns`
(booking channel / booking status / cargo status / payment method),
`topRoutes` by confirmed revenue, and seat-`occupancy` on upcoming
departures. Agent gets a light refresh (a 14-day counter-bookings
sparkline, no charts); the customer dashboard is a compact block appended
inside `Home.jsx` below the search hero (upcoming trips + counts + active
shipments, no analytics - a customer has 1-2 trips).

- **The daily `series` uses the first `@Query(nativeQuery = true)` in this
  codebase** - JPQL has no `date_trunc`. Buckets are
  `date_trunc('day', created_at AT TIME ZONE 'UTC')` - UTC, matching
  `hibernate.jdbc.time_zone: UTC` and how v1's "today" boundary works
  (a booking at 02:00 EAT counts to the previous UTC day - a known,
  deliberate simplification). `topRoutes` is also native (a
  `bookings→trips→routes` join, and those are plain UUID FK columns here,
  not mapped JPA relations). Everything else stays JPQL/derived.
- **Revenue = `SUM(bookings.total_amount)` for `status='confirmed'`** - the
  `payments` table is *not* summed for revenue (a payment is a separate
  optional staff action, not auto-created - see "Known gaps"); it only
  feeds the payment-method breakdown.

Routing: `App.jsx`'s `/` is now a `RoleHome` that renders the caller's own
dashboard (operator_admin/agent/platform_admin) or falls through to `Home`
(customer + logged-out guest). Stable deep-links exist too
(`/operator/dashboard`, `/agent/dashboard`, `/platform/dashboard`), each
`RequireRole`-wrapped; `AppShell` nav gained a "Dashboard" link per staff
role.

Frontend: **Recharts** is the first charting dependency in the repo
(`node-bff/frontend/package.json`) - lazy-loaded (`React.lazy` on the
operator/platform pages) so its ~115KB-gzip chunk is fetched *only* for
those two roles; the customer/guest/agent first load is unchanged. The
agent sparkline and stat-card sparklines are hand-rolled inline SVG
(`components/Sparkline.jsx`), no Recharts. New shared pieces:
`components/charts/{TrendLineChart,BreakdownDonut}.jsx`,
`components/{StatCard,Sparkline,TrendBadge,PeriodSelector,RankedBarList,
DashboardPanels}.jsx`, `lib/chartTheme.js` (literal hex - Recharts needs
colour strings; the categorical palette is the `dataviz` skill's validated
default). `PeriodSelector` persists the chosen window to
`localStorage["bustix.dashboard.period"]`. **No dark mode** - the app has
no theme system (`bg-slate-50` hardcoded), charts are light-palette only.

`DashboardIntegrationTest` covers all four endpoints (per-role gate,
seeded-data aggregates, `series` length per period, deltaPct, breakdown
keys, top-routes ordering) - compiles, same Testcontainers-unrun caveat as
the rest of the suite. Verified live end-to-end in a real Chrome browser
as all four demo users against the running dev stack: period selector
switching every chart/KPI, all breakdown donuts + leaderboards rendering
with real seeded numbers that cross-check against the existing list pages
(30 bookings = channel-donut total; 633.00 revenue = the 3 confirmed
bookings), the agent sparkline, the refreshed customer block, and the
role gate (operator_admin hitting `/platform/dashboard` redirects to `/`).
The two npm advisories `npm install` surfaced (`esbuild`/`vite`) are
pre-existing dev-server-only issues, unrelated to Recharts, with no effect
on the served production build.

## Pricing

v1 prices per trip, flat, regardless of seat (`trips.price`,
`Trip.getPrice()`). `seats.seat_class` exists and is populated (`standard`
default) but unused for pricing - reserved for a future
`seat_class -> multiplier` table, changing only how `BookingWriter` computes
`total_amount`, no schema migration needed.

## Per-operator settings

Added 2026-08-30. Before this, every operator ran on the same
platform-wide business config baked into `application.yml`
(`bustix.ticketing.*`). `operator_settings` (`V12`, one row per operator,
`tenant_id` PK - a **singleton**, not a collection) lets an
`operator_admin` override each of those values for their own operator,
carry operator contact / ticket-footer info, and govern the reschedule
notification behaviour.

- **`GET` / `PATCH /api/fleet/settings`** (`OPERATOR_ADMIN`,
  `OperatorSettingsController`, tenant-scoped via `TenantContext` like
  `RefundPolicyController`). No id, no `POST`/`DELETE`. `GET` returns
  `{overrides, effective, defaults}` (`OperatorSettingsResponse` - a
  purpose-built read shape like `WaybillWithItems`): `overrides` is the raw
  row (nullable fields, or `null` if no row yet), `effective` is
  overrides-merged-over-defaults, `defaults` is the `application.yml`
  values for the UI's "default: 0.15" hints.
- **The row is lazy** - `GET` works with no row (returns pure defaults);
  the row is created on the first `PATCH`. No backfill migration, no
  coupling to `OperatorProvisioningService`.
- **`PATCH` is a full replace of the override set**, not the app's usual
  partial update: the settings screen is one form that always submits its
  whole state, so a `null` field means "clear this override / revert to
  the platform default". Each override column is nullable;
  `reschedule_notifications_enabled` is `NOT NULL DEFAULT true`.
- **`OperatorSettingsService.resolve(tenantId)`** is the single merge
  point - it holds the `@Value` platform defaults (moved here out of
  `BookingWriter` / `BookingRescheduleService` / `TripController`) and
  coalesces each nullable override with its default. `resolve(null)` is
  safe (a tracked-but-never-issued waybill has no tenant) and returns pure
  defaults. Consumers now read `resolve(...)` instead of injecting the
  `@Value`s: `BookingWriter` (VAT), `BookingRescheduleService` (VAT +
  reschedule notice/fees + the notifications toggle), `TripController`
  (reporting buffer - `search()` memoizes per operator so a big result set
  isn't N lookups), `TripUpdateService` (the cascade below).
- **Overridable**: `vat_rate`, `reporting_buffer_minutes`,
  `reschedule_min_notice_hours`, `reschedule_fee_self_service`/
  `_counter`. **Still platform-only** (deliberately - not per-operator):
  `bustix.cargo.prohibited-items`, `bustix.seat-lock.ttl-seconds`,
  `bustix.tenant.org-claim-path`.
- **Contact / ticket info** (`support_phone` E.164-Ethiopian-validated,
  `support_email`, `support_address`, `website_url`,
  `ticket_footer_note`) surfaces on `TripSearchResult` (all five - feeds
  the customer/agent `BookingDetail` "ticket" views via the trip query)
  and, narrowed to phone+email, on `BookingTrackingView` /
  `WaybillTrackingView` (the public track pages). Nulls when the operator
  hasn't set them.
- **Trip-time-change notification cascade** (`TripUpdateService`, a new
  `@Transactional` bean `TripController.update` delegates to, same
  split-bean reason as `BookingWriter`): when `PATCH /api/fleet/trips/{id}`
  actually changes `departureAt` or `arrivalAt`, every `status='confirmed'`
  booking on the trip gets a `trip_rescheduled` outbox notification -
  **gated on `reschedule_notifications_enabled`** (default on), the same
  toggle that governs the per-booking `booking_rescheduled` notice in
  `BookingRescheduleService`. Guests (`customerUserId == null`) are
  skipped, same guard as every other notification write. A price-only edit
  and the `DELETE` (cancel) path notify nobody - the broader "trip
  lifecycle transitions" work is still separate.
- **Frontend: `/operator/settings` is the operator config hub.** Adding
  Settings brought the flat `operator_admin` nav to 8 items mixing fleet
  CRUD, config, and the cargo waybill workspace; it was consolidated the
  same day (2026-08-30) down to `Dashboard · Buses · Routes · Trips ·
  Cargo · Settings` (6). `pages/operator/SettingsLayout.jsx` renders a tab
  bar (`General` / `Refund Policies` / `Cargo Rates`) over `<Outlet/>`;
  the three tabs are child routes of `/operator/settings` (still
  deep-linkable), `General` = `Settings.jsx` (the `PATCH /api/fleet/settings`
  form), the other two = the unchanged `RefundPolicies.jsx` /
  `CargoRates.jsx` pages (just their top-level `<h1>` dropped - the tab
  labels them now). The old top-level paths
  `/operator/{refund-policies,cargo-rates}` `<Navigate replace>` into the
  tabs so existing links/bookmarks still work. `RefundPolicyController` /
  `CargoRateController` (`/api/fleet/refund-policies`,
  `/api/fleet/cargo-rates`) are **unchanged** - this was frontend-only.
  Supersedes the Phase 3 note's "four direct links, no sub-nav" claim.

Verified live end-to-end against the running dev stack (`V12` applied,
Hibernate `validate` passed on boot): `GET` returns defaults with
`overrides:null`; `PATCH` (vat 0.10, notice 24h, phone, footer, toggle
off) persists and `effective` reflects it while un-set fields stay
default; invalid `vatRate`/`supportPhone` → 400; agent → 403; a booking
under the 0.10 override taxed at 10% not 15%; the trip contact fields
appear in `/api/trips/search` and `/api/trips/{id}`; a departure-time
`PATCH` queued **no** `trip_rescheduled` row with the toggle off and
exactly one (for the booked customer) with it on; a price-only edit
queued none. Test row + notifications deleted afterward; `operator_settings`
left empty. `OperatorSettingsIntegrationTest` /
`TripRescheduleNotificationIntegrationTest` plus new cases on
`BookingIntegrationTest` (VAT override) and `TenantIsolationIntegrationTest`
(one operator's settings invisible to / unaffected by another) cover it -
compile clean, same Testcontainers-unrun-on-this-machine caveat as the
rest of the suite. The config-hub consolidation was verified in a real
browser as `demo-operator-admin`: the 6-item nav, all three tabs
rendering their full CRUD UIs, deep-links, and the old-path redirects.

## Refund & cancellation

Two cancellation endpoints, both in `CancellationController`, kept separate
rather than one path branching on role because their booking lookups are
scoped completely differently:

- `POST /api/bookings/{bookingId}/cancel` (`AGENT` or `OPERATOR_ADMIN`) -
  the original staff path. Looks the booking up **tenant-scoped** via
  `TenantContext` (an agent can only cancel their own operator's bookings).
- `POST /api/my-bookings/{bookingId}/cancel` (`CUSTOMER`) - customer
  self-service, added 2026-08-23 (previously a known gap - don't assume it
  doesn't exist just because older notes here said so). Looks the booking
  up **ownership-scoped** via `customerUserId` instead
  (`BookingRepository.findByIdAndCustomerUserId`), since a customer token
  carries no tenant to scope by (see `TenantContext`'s javadoc). A different
  customer's booking 404s, same as a different operator's booking does on
  the staff path - ownership/tenant checks never distinguish "exists but not
  yours" from "doesn't exist."

Both call into the same `CancellationService`: `cancel(...)` and
`cancelAsCustomer(...)` each resolve (and access-check) their own `Booking`,
then hand off to a shared private `applyCancellation(Booking, ...)` that
computes the refund, flips the booking to `cancelled`, frees its seats back
to `open`, records a `cancellations` row, and writes a `booking_cancelled`
outbox notification - all inside whichever public method's own
`@Transactional` boundary (same shape as `BookingWriter.write`; see
`applyCancellation`'s javadoc for why it's safe to call from either without
its own `@Transactional`).

`GET /api/my-bookings` and `GET /api/my-bookings/{bookingId}` (`CUSTOMER`,
`BookingController`, added 2026-08-23) are the natural companion to
`cancelAsCustomer` above: a customer could already cancel a booking they
knew the id of, but had no way to look that id up in the first place.
Same ownership-scoped shape, via the new
`BookingRepository.findAllByCustomerUserId`/`findByIdAndCustomerUserId`.

**Refund policy** (`refund_policies`): each operator configures an ordered
list of tiers keyed by hours-before-departure:
```json
[
  {"cutoff_hours": 24, "refund_percent": 100},
  {"cutoff_hours": 2,  "refund_percent": 50},
  {"cutoff_hours": 0,  "refund_percent": 0}
]
```
`route_id NULL` is the operator-wide default; a row with a specific
`route_id` overrides it for that route only. `RefundCalculator` picks the
route-specific policy if one exists, falls back to the operator-wide
default, sorts tiers highest-cutoff-first (don't rely on insertion order),
applies the first tier whose cutoff the booking's notice period clears. **If
the operator hasn't configured a policy, the refund is zero** rather than
failing the cancellation - a missing policy is a config gap, not grounds to
block cancellation.

`GET/POST/PATCH/DELETE /api/fleet/refund-policies(/{id})`
(`OPERATOR_ADMIN`, `RefundPolicyController`, added 2026-08-23) is how those
tiers actually get configured now - previously the only way was hand-running
SQL (see the README's "seed a refund policy" step, now just an
example of what the endpoint does under the hood). `POST`/`PATCH` take
structured `tiers` (a list of `{cutoff_hours, refund_percent}`, reusing
`RefundTier` with `@Min`/`@Max` added so a malformed tier is rejected at the
API boundary - see its javadoc for why those constraints don't affect the
existing DB-read path) rather than a raw JSON string, but the response still
returns the entity directly like every other fleet controller, so `rules`
comes back as an escaped JSON string, not a nested array - deliberately
consistent with the rest of this API's no-DTO-layer convention rather than
special-cased just for this one entity. `route_id` isn't editable via
`PATCH` (delete-and-recreate instead - see `UpdateRefundPolicyRequest`'s
javadoc for why). `DELETE` here is a **real delete**, unlike buses/routes/
trips above - a refund policy is operator configuration, not a financial or
audit record, and `RefundCalculator`'s "no policy = 0% refund" fallback
means removing one is always safe.

`GET/POST/PATCH /api/bookings/{bookingId}/payments(/{id})`
(`AGENT`/`OPERATOR_ADMIN`, `PaymentController`, added 2026-08-23) records
payments collected against a booking - the `payments` table has existed
since V1 (cash today, real gateway later) but nothing wrote to it before
this. Tenant-scoped through the booking (payments carries no `tenant_id` of
its own, same shape as `booking_seats`/`cancellations`/`notifications`) -
the booking lookup doubles as both existence and ownership checks, same
pattern `CancellationController` uses. **No `DELETE`**: a payment is a
financial fact, not something with a safe "soft-deactivate" reading the way
a bus/route/trip has - correcting one after the fact should really mean an
adjusting entry, not editing/removing history, but that flow doesn't exist
yet (`PATCH` today just corrects a miskeying before reconciliation, nothing
fancier).

## Platform administration

`platform_admin` was, until 2026-08-23, fully wired through auth (JWT
parsing, `TenantContext` staying empty for it, `CurrentUserService`) with
**zero endpoints actually checking for it** - onboarding a new operator was
a manual `infra/keycloak/create-demo-org.sh` run followed by hand-running
the SQL insert it prints. `GET/POST/PATCH/DELETE
/api/platform/operators(/{id})` (`PLATFORM_ADMIN`, `PlatformController`,
full CRUD as of 2026-08-23) is now that surface. `POST`
(`OperatorProvisioningService.provisionOperator`) creates the Keycloak
Organization via the Admin REST API first (`KeycloakOrganizationClient` -
the same two calls `create-demo-org.sh` makes by hand: a password-grant
admin login against the master realm's `admin-cli` client, then `POST
/admin/realms/bustix/organizations`), then inserts the local `operators`
row. No compensating rollback to Keycloak if the DB insert fails after the
org was already created - same caveat the shell script already carried for
its manual version of this flow, not solved here either. `PATCH` only
allows editing `name` - `keycloak_org_id` (the alias) is fixed at creation,
since `TenantContextFilter` matches it against a staff token's organization
claim and changing it would silently break tenant resolution for every
existing staff login at that operator. `DELETE` reuses the `status` column
`operators` already had since V1 (no migration needed, unlike buses/routes)
to soft-deactivate rather than delete the row - an operator can have
buses/routes/trips/bookings underneath it. As of 2026-08-26,
`BookingService.createBooking` reads `status` and blocks new bookings
against a deactivated operator's trips (see "Operator status enforcement"
below) - deliberately booking-time-only, not extended to staff login or
marketplace search.

`KeycloakOrganizationClient` is a plain `RestClient`, not the
`org.keycloak:keycloak-admin-client` library - that library's own RESTEasy
client and Jackson versions risk classpath conflicts with Spring's stack,
not worth it for two HTTP calls. Config lives under
`bustix.keycloak-admin.*` in `application.yml`
(`KEYCLOAK_ADMIN_BASE_URL`/`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD`) -
`base-url` is the bare Keycloak server URL, deliberately not the same
property as `issuer-uri` below (that one includes `/realms/bustix` and is
for JWT validation; this always talks to the master realm regardless of
`bustix`'s own client config). `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD`
reuse the exact env var names `docker-compose.yml` already seeds Keycloak's
own container with, rather than inventing new ones - one source of truth
for "the Keycloak admin credentials."

## Auth / BFF details

- Keycloak realm `bustix`, four realm roles: `platform_admin`,
  `operator_admin`, `agent`, `customer` (`infra/keycloak/realm-export.json`).
- `bus-ticketing-bff` is a confidential client with PKCE (S256), redirect URI
  `http://localhost:3000/auth/callback`. Its client secret is
  auto-generated by Keycloak on import (not pinned in the realm export) -
  see `.env.example`.
- `node-bff` does OIDC discovery against `KEYCLOAK_ISSUER` (internal
  `http://keycloak:8080/...`, container-to-container) but rewrites
  `authorization_endpoint`/`end_session_endpoint` to `KEYCLOAK_ISSUER_PUBLIC`
  (`http://localhost:8080/...`) before handing URLs to the browser, since the
  browser can't resolve the `keycloak` hostname. Full reasoning (including
  why `issuer.metadata.issuer` itself is *not* rewritten) is in the comment
  atop `node-bff/src/auth/oidc.js`.
- Sessions live in Redis (`connect-redis`), not memory, so the BFF can
  restart or scale to more than one instance without logging everyone out.
- Everything under `/api` (`node-bff/src/routes/api.js`) requires a session
  except the paths in its `PUBLIC_ROUTES` table (cargo tracking, trip
  search/locations/seats/detail, guest booking creation/tracking - see
  "Guest booking & customer shipment history" below), refreshes the access
  token first if expired, then forwards the request as-is to
  `spring-boot-api` with the token attached as a Bearer header - the
  browser never holds or sends a token itself, only the BFF's session
  cookie. Spring Security re-validates that token and re-derives
  `TenantContext` from it exactly as if the caller had sent it directly.
- `req.session.tokenSet` round-trips through Redis as plain JSON between
  requests (`connect-redis` serializes with `JSON.stringify`), so on every
  request after the one that set it, it's a plain object, not the real
  `openid-client` `TokenSet` instance - none of `TokenSet`'s methods survive
  that round-trip, only its plain fields. `refreshIfExpired` rewraps it
  (`new TokenSet(req.session.tokenSet)`) before calling `.expired()` for
  this reason - don't call `TokenSet` methods on `req.session.tokenSet`
  directly anywhere else without doing the same.
- `SecurityConfig` needs `@EnableMethodSecurity` for `@PreAuthorize` to be
  evaluated at all - Spring Security 6 does **not** enable method-level
  security automatically just because `@PreAuthorize` annotations are
  present. This was missing until 2026-08-23; every `@PreAuthorize` check
  in the codebase (`BookingController`, `CancellationController`,
  `TripController`, `BusController`, `RouteController`) was silently inert
  before then - any authenticated token of any role could call any of them.
  If you ever see `@PreAuthorize` added to a new controller with no effect,
  check this annotation is still there before debugging anything else.

## Frontend

Added 2026-08-24. Previously `node-bff`'s `/` route was a one-line HTML stub
- there was no UI at all, only the JSON REST API. `node-bff/frontend/` is a
React + Vite + Tailwind SPA, its own `package.json`/lockfile/`npm install`/
`npm run build` lifecycle, **not** an npm workspace of `node-bff` itself
(`node-bff/package.json` gets zero new dependencies). Lives nested under
`node-bff/` specifically because `docker-compose.yml`'s `node-bff` service
builds with context `./node-bff` - a Dockerfile can't `COPY` outside its own
build context, so nesting here means zero changes to `docker-compose.yml`.

**Build/serve wiring**: `node-bff/Dockerfile` is now multi-stage - a
`frontend-build` stage runs `npm ci && npm run build` inside `frontend/`,
and its `dist/` output is `COPY --from=`'d into the runtime stage as
`./public`. `node-bff/src/index.js` serves `public/` via `express.static`
and falls back to `public/index.html` for any GET that isn't `/health`,
`/auth/*`, or `/api/*` (client-side SPA routing) - falling back further to
the pre-frontend stub HTML/JSON when no build is present at all, so
`node --test` and pure-backend local dev keep working unmodified. Confirmed
working through every layer: `:3000` directly, and through nginx's existing
`location /` catch-all (unchanged - nginx needed zero config changes).
**Local dev** (hot reload): `npm run dev` in `frontend/` runs Vite on
`:5173`, proxying `/api` and `/auth` to `node-bff` on `:3000` (the session
cookie is host-only, shared transparently across `localhost` ports) - one
wrinkle, not fixable without changing Keycloak's client config: login always
completes on `:3000` (that's the hardcoded registered redirect URI), so the
one-time-per-session workflow is log in at `:3000/auth/login`, then switch
the tab to `:5173` for hot-reloading dev.

**Auth in the SPA**: role info for UX-only nav/route gating comes from
`GET /auth/me`. **Confirmed against a real token**: the OIDC ID token
(`tokenSet.claims()`, what `req.session.user` held before) carries no
`realm_access.roles` claim at all on this realm - only the access token
does. `node-bff/src/routes/auth.js`'s `/callback` now decodes the access
token's payload (`decodeJwtPayload` - no signature verification needed,
its authenticity was already established by the OAuth token exchange) and
merges `realm_access.roles` into `req.session.user` before storing it, so
`/auth/me` exposes it. Real authorization stays entirely server-side
(`@PreAuthorize`) regardless - this is UX only.

**A real, subtle bug found and fixed while building this** (not a frontend
bug - it broke every no-body `POST`/`PATCH`/`DELETE` call through the BFF,
so it would have bitten booking cancellation, fleet deactivate, refund-
policy delete, all of it, the moment any client without a request body
called them through `node-bff`): `express.json()` sets `req.body` to `{}`
for a request with **no** body and no `Content-Type` header at all (not
`undefined`) - `node-bff/src/routes/api.js`'s `forwardToApi` was treating
that as "has a body" and forwarding it, with Node's `fetch()` silently
defaulting to `Content-Type: text/plain;charset=UTF-8` since nothing
overrode it. `spring-boot-api`'s `@RequestBody(required = false)`
parameters (e.g. `CancellationController.cancelMyBooking`, which doesn't
even reference the body in its `@PreAuthorize` check) turned that into a
403 `insufficient_scope` rather than the plain success a genuinely empty
body should get - confirmed by curl reproducing the exact byte-for-byte
request directly against `spring-boot-api` both ways. Fixed via a new
`shouldForwardBody(method, body)` helper (exported and unit-tested in
`node-bff/test/api.test.js`) that only forwards a body - always with an
explicit `Content-Type: application/json` - when one actually has content.

**Design system**: semantic Tailwind tokens (not raw palette names) -
`brand` (header/nav/links), `accent` (primary CTAs), `success`/`danger`/
`warning` (seat/status states), all in `frontend/tailwind.config.js`.
Inter (UI text) + JetBrains Mono (booking refs, seat numbers, prices),
self-hosted via `@fontsource/*`, not a CDN link. `SeatMap` groups a trip's
flat seat list into visual rows by `seatNo`'s leading digits (mirroring
`SeatLayoutGenerator`'s own row convention) with an aisle gap split at the
row's midpoint - an approximation, not an exact reproduction of the bus's
own `seatLayout` string, since the seats endpoint doesn't return it.

**API client**: `@tanstack/react-query` over a thin `apiFetch()` wrapper
(`frontend/src/api/client.js`) - no generated typed client (no OpenAPI spec
exists anywhere in `spring-boot-api` to generate from) and no TypeScript
(matches the rest of the repo). Every `@ExceptionHandler` across the whole
API returns a plain `String`, not a JSON envelope, so error bodies are read
as text. A `401` from any `/api/*` call triggers a full redirect to
`/auth/login` centrally, rather than every page handling it separately.

**Built so far (Phase 1, customer flow)**: search → seat selection → book
→ booking confirmation/detail → self-cancel → My Bookings, all verified
live in a real browser against the real running stack (not just curl) -
including the seat-conflict `409` path and the bug above. Two small
`spring-boot-api` additions were needed to support it and were added the
same session: `GET /api/trips/{tripId}` (single-trip lookup for
`CUSTOMER`/`AGENT`, not filtered to `status=scheduled` like search - a
customer needs to see past/cancelled trips in their history too) and
`GET /api/my-bookings/{bookingId}/seats` (`BookedSeatView`, joining
`booking_seats`/`seats` since neither table alone has both seat numbers and
price paid).

**Phase 1.5 (2026-08-24)**: fixed to match the "Ticketing" backend change
above - `SeatSelection.jsx`'s booking call was broken (old `seatIds`
shape) until this pass. New shared `components/PassengerDetailsForm.jsx`
collects a name (required) + phone (optional) per selected seat; the first
seat defaults to the logged-in customer's own name (`useAuth()`'s
`user.name`/`preferred_username`), further seats start blank. Also surfaced
`ticketNumber`/`bookingRef`/the subtotal+VAT split on `BookingDetail.jsx`,
already returned by the API but not previously shown anywhere.

**Phase 2 (agent/counter, 2026-08-24)**: `pages/agent/` - `Search.jsx` →
`SearchResults.jsx` → `SeatSelection.jsx` (same `useCreateBooking` call as
the customer flow; spring-boot-api decides `channel=counter` from the JWT
role alone, not anything this page sends) → `BookingDetail.jsx`, plus a
`Bookings.jsx` list. Reuses every shared component from Phase 1
(`SeatMap`, `TripCard`, `StatusPill`, `EmptyState`, `ErrorBanner`,
`Skeleton`) rather than introducing agent-specific variants of them - only
the destination routes differ (`/agent/trips/:id` instead of `/trips/:id`,
etc.), which is why these are separate thin page components rather than
one set of pages parametrized by role. `PassengerDetailsForm` gets
`showIdFields` here (agent flow only) since the counter has the walk-in
customer's ID in hand at the point of sale, unlike online self-service.
`BookingDetail.jsx` adds the two things a customer's own view has no
business doing: staff cancel (`POST /api/bookings/{id}/cancel`, not the
customer's ownership-scoped path) and payment recording
(`POST /api/bookings/{id}/payments`, cash/telebirr/cbe_birr/card, shown
against a running "collected of total" balance). New query hooks in
`api/queries.js`: `useAgentBookings`/`useAgentBooking`/
`useAgentBookingSeats`/`useCancelBooking`/`usePayments`/`useCreatePayment`.
`AppShell`'s nav gets an agent-gated section (`hasRole('agent')`) alongside
the existing customer one. Verified live end-to-end in a real browser as
`demo-agent` (search → book → seat map → passenger form → confirmation
with ticket number/PNR/VAT → record a payment → cancel with computed
refund) and, separately, the Phase 1.5 fix as `demo-customer` - both
booking/cancel cycles cleaned up afterward, no leftover state.

**Phase 3 (operator_admin fleet management, 2026-08-25)**: `pages/operator/`
- `Buses.jsx`, `Routes.jsx`, `Trips.jsx`, `RefundPolicies.jsx`, each a
self-contained list-plus-inline-edit page (a create form up top, existing
rows toggle into an inline edit form in place rather than a modal/dialog -
no such component exists anywhere else in this app either, so this keeps
the pattern consistent) against
`/api/fleet/{buses,routes,trips,refund-policies}`. `Trips.jsx` additionally
loads the buses/routes lists (already needed for its own create-trip
dropdowns) to resolve `routeId`/`busId` to display names client-side, since
`GET /api/fleet/trips` returns bare `Trip` rows, not a denormalized shape
like the customer-facing `TripSearchResult`. `RefundPolicies.jsx` is the
one page that has to translate on the way in and out: `RefundTier`'s
`@JsonProperty` means the API's `tiers` are snake_case
(`cutoff_hours`/`refund_percent`) on the wire, converted to/from this
page's camelCase editing state by two small helpers
(`parseTiers`/`toApiTiers`) - see the file for why. `AppShell` nav gets an
`operator_admin`-gated section (four direct links, no sub-nav/landing page
- simplest thing that works for four items). New query hooks in
`api/queries.js`: `useFleetBuses`/`useCreateBus`/`useUpdateBus` and the
same three-hook shape for routes/trips, plus `useCancelTrip` and
`useRefundPolicies`/`useCreateRefundPolicy`/`useUpdateRefundPolicy`/
`useDeleteRefundPolicy`. Verified live in a real browser as
`demo-operator-admin`: created/edited/deactivated/reactivated a bus,
created a route (with terminal fields), created/edited/cancelled a trip
(route+bus dropdowns, datetime-local conversion), created/edited/deleted a
route-specific refund policy - all test rows removed again afterward
(buses/routes have no delete API, being soft-deactivate-only by design, so
cleanup for these specifically went through direct SQL rather than the
API, same as prior sessions' CRUD-verification cleanups).

**Phase 4 (platform_admin operator onboarding, 2026-08-25)**:
`pages/platform/Operators.jsx` - one page (a single entity, unlike the
three-page fleet-management phase above), same create-form-plus-inline-
edit pattern against `/api/platform/operators`. The one thing this create
form does that no other create form in the app does: its `POST` reaches
out to a real Keycloak Organization first
(`OperatorProvisioningService`), not just Postgres, so it's slower and can
fail in more ways (taken org alias, Keycloak unreachable). `orgAlias`
isn't part of the edit form - only `name`/`tin` are, matching
`UpdateOperatorRequest`'s deliberately narrow shape (changing the alias
post-creation would break `TenantContextFilter`'s tenant resolution for
every existing staff login at that operator). Deactivated rows show no
reactivate button - `PlatformController` genuinely has no reactivate
endpoint (see its own javadoc), so the UI doesn't pretend one exists.
`AppShell` nav gets a `platform_admin`-gated "Operators" link. New query
hooks: `usePlatformOperators`/`useCreateOperator`/`useUpdateOperator`/
`useDeactivateOperator`. Verified live as `demo-platform-admin`: created
an operator (confirmed a real Keycloak Organization was provisioned, not
just the local row), edited its TIN, deactivated it (reactivate button
correctly absent) - then fully cleaned up afterward through both systems
(the Organization via the Keycloak admin API, the row via direct SQL,
since `DELETE` here only soft-deactivates).

This completes the fourth and final planned frontend phase - customer,
agent/counter, operator_admin, and platform_admin all now have working
UIs end to end.

**Two more real bugs found live 2026-08-24, using the customer flow as an
actual user rather than through curl:**

1. **Trip search was case-sensitive with no indication why.** Typing "addis
   ababa" (any casing other than exactly how the operator entered the route,
   e.g. "Addis Ababa") returned zero results. `RouteRepository`'s search
   query was an exact-match derived query
   (`findAllByOriginAndDestination`). Fixed by renaming it to
   `findAllByOriginIgnoreCaseAndDestinationIgnoreCase` (Spring Data compiles
   `IgnoreCase` to a `LOWER(...)` comparison - can't use the plain
   `idx_routes_search` btree index the way an exact match could, acceptable
   at this app's scale, a functional index on `LOWER(origin), LOWER(destination)`
   would be the real fix if this table ever gets large) and trimming both
   params in `TripController.search()`.
2. **An expired refresh token surfaced as an opaque 500, not a login
   prompt.** A browser tab left open long enough for Keycloak's *refresh*
   token (not just the short-lived access token) to lapse hit
   `refreshIfExpired`'s `if (tokenSet.expired() && tokenSet.refresh_token)`
   branch, attempted a refresh, and openid-client's `Client.refresh()`
   threw an `OPError` (`error: 'invalid_grant'`, `error_description: 'Token
   is not active'`) that previously just fell through to `next(err)` and
   the generic `{"error":"Internal BFF error"}` 500 handler - a completely
   unrecoverable session (the user *must* log in again) looked
   indistinguishable from a real server fault. Fixed in `refreshIfExpired`
   (`node-bff/src/routes/api.js`): an `invalid_grant` `OPError` from the
   refresh call now destroys the session and returns the same `401` shape
   `requireSession` already returns for "no session at all" - the
   frontend's existing central 401-handling in `apiFetch` already turns
   that into a clean redirect to `/auth/login`, no separate handling
   needed. Both fixes are unit-tested/live-verified: the search fix via a
   real browser search after the case fix, the session fix by watching a
   real browser tab redirect cleanly to Keycloak login instead of showing
   the error banner. Root-caused by realizing the request-scoped log files
   this session had been checking were stale (the actually-listening
   process's stdout wasn't landing in them) - restarting both services with
   plain, unredirected-through-PowerShell log capture surfaced the real
   stack trace immediately. If a similar "nothing in the log despite a live
   500" situation recurs, suspect the log file before the code.

## Known gaps (don't assume these are implemented)

- `payments` has a full CRUD API now (see "Refund & cancellation" above),
  but it's still not *automatically* wired into the booking/cancellation
  flow - creating a booking doesn't require or create a payment, and
  cancelling one doesn't automatically void/reverse any payment recorded
  against it. Recording a payment is a deliberate, separate staff action.
- **Operator isolation** was audited/hardened 2026-08-27 (see "Tenancy
  model" + `TenantIsolationIntegrationTest`) and is solid, but two
  *intentional* residuals remain: (1) a deactivated operator's trips still
  appear in marketplace search (`TripController.search`) - only the final
  booking call and staff API access are blocked, not visibility; (2)
  `PlatformController` has no reactivate endpoint, so undoing a
  deactivation is still a direct-SQL `UPDATE operators SET status='active'`.
  Neither is an oversight; both are follow-ups if they start to bite.
- Email is a stub (`LoggingEmailSender` just logs) - the outbox table,
  retry, and status-tracking machinery around it doesn't need to change when
  a real `NotificationSender` is swapped in.
- Focused unit tests exist (`SeatLayoutGeneratorTest`, `RefundCalculatorTest`,
  `TenantContextFilterTest` in spring-boot-api; `test/api.test.js` in
  node-bff for the `TokenSet`-rewrap logic). As of 2026-08-23,
  controller/integration-level coverage now exists too:
  `AbstractIntegrationTest` (in `com.bustix.support`) spins up a real
  `@SpringBootTest`/`MockMvc` context against Postgres + Redis via
  Testcontainers (images match docker-compose's `postgres:16-alpine`/
  `redis:7-alpine`) and drives requests through the actual filter chain -
  Spring Security, `TenantContextFilter`, `@PreAuthorize` all run for real.
  Only JWT issuance is faked, via Spring Security Test's `jwt()` request
  post-processor: it builds a `Jwt` directly and pulls authorities from the
  app's real `JwtAuthenticationConverter` bean
  (`jwtAuthenticationConverter.convert(jwt).getAuthorities()`) rather than
  re-implementing the ROLE_-prefix mapping - see `NoNetworkJwtDecoderConfig`
  in the same file for why the autoconfigured `JwtDecoder` is replaced (it
  otherwise does eager OIDC discovery against `KEYCLOAK_ISSUER_URI` at
  context startup). `TripSearchIntegrationTest`, `FleetIntegrationTest`,
  `BookingIntegrationTest`, and `CancellationIntegrationTest` cover the
  marketplace search, fleet CRUD, booking (both channels, idempotency, seat
  conflict, cross-tenant-agent 403), and cancellation (both endpoints: staff
  refund calculation/already-cancelled 409/cross-tenant 404, and customer
  self-service success/ownership 404/already-cancelled 409/role-blocked 403
  in both directions) endpoints respectively - found and fixed
  `TenantMismatchException`'s missing 403 mapping along the way (see the
  Booking flow section above). Customer self-service cancellation itself
  (added 2026-08-23, see Refund & cancellation above) was additionally
  verified live end-to-end - real login through nginx, a real booking, a
  real self-cancel with a real computed refund, a 409 on re-cancel, and a
  403 for a staff token on the customer-only route, all against the running
  dev stack - not just the (still Testcontainers-blocked) integration
  suite, so this specific feature's HTTP-level behavior is confirmed
  working today regardless of that suite's local-verification gap.
  **A first attempt at this** (2026-08-23, since reverted) tried exposing
  the JWT-authorities mapping as its own
  `@Bean Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter`
  in `SecurityConfig` for tests to reuse directly - this broke
  `mvn spring-boot:run` outright (not just tests): Spring Boot's
  `WebMvcAutoConfiguration` auto-registers every `Converter`-typed bean into
  the global MVC `ConversionService`, and since the converter was a lambda,
  Spring couldn't reflectively resolve its generic `<S,T>` types, throwing
  `IllegalArgumentException: Unable to determine source type <S> and target
  type <T>` and failing the whole context at startup. `SecurityConfig` now
  keeps that mapping private (inlined into the `jwtAuthenticationConverter`
  bean, same as before this session), with a comment warning against
  re-extracting it as a `Converter` bean again.
  **Not yet verified via Testcontainers on this machine**: this machine's
  Docker Desktop (reporting server version 29.7.2) rejects Testcontainers'
  connection over the Windows named pipe with a malformed `400` on every
  attempt, tried against both the default and `desktop-linux` context
  pipes, Testcontainers 1.19.8 (Spring Boot 3.3.4's managed version) and
  1.20.4 (pinned via the `testcontainers.version` property in `pom.xml`),
  and exposing the daemon on `tcp://localhost:2375` (didn't take - nothing
  ends up listening on 2375/2376 even after a full Docker Desktop restart).
  The `docker` CLI itself works fine throughout, so this is specifically a
  Testcontainers/docker-java Windows-npipe incompatibility, not a real
  Docker outage - the manually-run services (below) prove the daemon itself
  is healthy. WSL2 (`wsl -d Ubuntu`) does reach the daemon cleanly once
  Docker Desktop's WSL integration is enabled for it, but running the suite
  from there needs Java 21 + Maven installed inside Ubuntu first, which
  needs an interactive `sudo` password the agent can't supply - left for
  the user to do manually (`wsl -d Ubuntu -- sudo apt-get install -y
  openjdk-21-jdk-headless maven`), or accept CI as the first real run. All
  four test classes and the shared fixture/auth-builder base do compile
  cleanly (`mvn test-compile`) and the app itself starts and serves traffic
  correctly again (manually verified end-to-end same day) - only the
  Testcontainers-driven suite itself is unconfirmed.
- **Gotcha found while chasing the above (2026-08-23)**: if the `redis`
  container disappears while `node-bff` is still running against it,
  node-bff's Redis client can end up wedged holding a raw bind on port 6379
  on the Windows host itself. Every subsequent attempt to start the `redis`
  container then fails with `ports are not available ... bind: Only one
  usage of each socket address` - this looks exactly like a Docker
  Desktop/WSL2 networking bug (survives restarting the container, deleting
  the image, and even fully quitting and reopening Docker Desktop), but
  isn't one. Diagnose with `Get-NetTCPConnection -LocalPort 6379` - if
  `OwningProcess` is node-bff's PID rather than a docker process, restart
  node-bff first, then start `redis`, then restart node-bff again.
- `nginx` (the `:80` single-entry-point) was exercised end-to-end for the
  first time on 2026-08-23 (login, session check via `/auth/me`, an
  authenticated `/api/trips/search` call, and logout all scripted through
  `http://localhost` with curl + a cookie jar, mimicking a real browser) -
  it works correctly. On this machine (native Keycloak/node-bff, not
  dockerized - see [[local-dev-infra]] in the user's memory), nginx itself
  still runs as the one `docker compose` service actually in use for it,
  started via `docker compose up -d --no-deps nginx`; a
  `docker-compose.override.yml` `extra_hosts` entry (`node-bff:host-gateway`,
  `keycloak:host-gateway`, alongside the existing Keycloak-DB override)
  points the container's already-committed `nginx.conf` - which hardcodes
  the Docker service names `node-bff:3000` and `keycloak:8080` as upstreams
  - straight through to the natively-running processes on the host, with
  zero changes to the committed config.
  **One architectural note, not a bug**: `node-bff`'s `BFF_BASE_URL` (used
  as both the OAuth `redirect_uri` and the post-logout redirect) is
  `http://localhost:3000`, so Keycloak sends the browser directly back to
  port 3000 after login/logout, bypassing nginx's `:80` for those two hops
  specifically - everything else (the initial `/auth/login` redirect,
  `/auth/me`, and all `/api/*` calls) does go through nginx. This works
  locally because cookies aren't port-scoped and 3000 is reachable
  alongside 80, but it means nginx isn't quite a *sole* entry point end to
  end - a deployment that only exposed `:80` externally would need
  `BFF_BASE_URL` (and the Keycloak client's registered redirect URI) to
  point through nginx instead. Not changed, since that's a real deployment
  decision, not a local dev-environment fix.
- The new frontend (see "Frontend" above) now covers the customer flow
  (fixed 2026-08-24 after the ticketing changes broke it), the
  agent/counter flow (built 2026-08-24), operator_admin fleet+refund-
  policy management, and platform_admin operator onboarding (both built
  2026-08-25) - every role now has a working UI end to end. A `demo-agent`
  Keycloak user exists (`agent` role, member of the `demo-bus-co`
  Organization - see [[bus-ticketing-demo-credentials]]), created
  2026-08-24 the same way
  `demo-platform-admin` was. **Getting its token requires a temporary admin
  workaround**: `bus-ticketing-bff`'s `directAccessGrantsEnabled` is `false`
  (only the real Authorization Code + PKCE flow is meant to issue tokens),
  so a curl-based password-grant login needs it flipped to `true` first via
  a full GET-modify-PUT of the client's config (never a partial PUT - that
  would wipe every other client setting, redirect URIs and PKCE included)
  and flipped back to `false` immediately after. Fine for one-off live
  verification from this machine; not something to leave enabled or wire
  into any script that runs unattended.
- **`my-notes/ethiopian_bus_system_specs.md`** is a BRD for a considerably
  bigger system than what's built - the "Ticketing", "Age-based fares",
  "Boarding Gate State Machine", "Rescheduling", and (as of 2026-08-25)
  "Cargo & Logistics Module" sections above are what's been implemented
  from it so far, each a deliberate, separately-scoped decision, not the
  whole document at once. The BRD's fixed 90/75/50/0% cancellation tiers
  were deliberately **not** adopted as a replacement for the existing
  configurable `refund_policies` feature (reused as-is for cargo
  cancellations too) - if you want them as an example policy, seed them
  through `RefundPolicyController` like any other operator-specific
  policy, don't hardcode them. The Cargo module itself was deliberately
  scoped narrower than the BRD's full section 3 - see
  `my-notes/cargo_logistics_scope_v1.md` and the "Cargo & Logistics
  Module" section above for exactly what's built vs. still deferred at
  that point. As of 2026-08-26 the three gaps that section's "explicitly
  out of scope" list named - multi-item waybills, `payments`-table
  integration, customer-initiated creation - are all built; see "Cargo
  module: multi-item waybills, payments ledger, customer-initiated
  requests" below. What's still genuinely missing: multi-item-per-waybill
  is one flat item list per waybill, not nested/grouped sub-shipments; no
  notification-outbox wiring for cargo status changes.
