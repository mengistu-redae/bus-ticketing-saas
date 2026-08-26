# Bus Ticketing SaaS

[![GitHub repo](https://img.shields.io/badge/GitHub-bus--ticketing--saas-blue?logo=github)](https://github.com/mengistu-redae/bus-ticketing-saas)

A multi-tenant bus ticketing platform: bus operators (tenants) manage their
own fleet, routes and trips; customers search and book across every operator
on the platform; operator staff can also book and cancel on behalf of
walk-in customers at the counter.

```
 browser --> nginx --> node-bff (session, OIDC) --> spring-boot-api (JWT, tenant-aware)
                 \-> keycloak (login only, admin console)      \-> postgres, redis
```

- **spring-boot-api** - the tenant-aware core API. Stateless, validates
  bearer JWTs, never talks to the browser directly.
- **node-bff** - the only thing the browser talks to. Runs the OAuth2
  Authorization Code + PKCE flow against Keycloak, holds tokens in a
  server-side (Redis-backed) session, and forwards them to the API as a
  Bearer header. The browser only ever sees a session cookie.
- **keycloak** - identity provider. One realm (`bustix`), four realm roles,
  and the Organizations feature for grouping operator staff by tenant.
- **postgres / redis** - primary datastore and seat-locking / session store,
  respectively.
- **nginx** - single entry point on `:80` for local dev; routes everything
  to node-bff except `/keycloak/*`, which reaches the admin console.

## How tenant filtering works

`tenant_id` on `operators` is the internal tenant key - see the comment at
the top of `V1__init.sql` for why it's not just the Keycloak organization id
directly.

There is deliberately **no** blanket Hibernate multi-tenant filter. Instead:

- `TenantContextFilter` runs once per request (after JWT auth), reads the
  org claim off a staff token, resolves it to an `operators.id` via
  `OperatorRepository.findByKeycloakOrgId`, and stashes it in the
  request-scoped `TenantContext`.
- Every staff-scoped repository method takes the tenant id **explicitly** as
  a parameter (`findByTenantIdAndId(...)`, `findAllByTenantId(...)`), rather
  than a query implicitly reading `TenantContext` deep inside some shared
  base repository. You can tell whether an endpoint is tenant-scoped or
  cross-tenant just by reading its method signature - no need to trace
  through filter config to find out.
- `TenantContext` is empty (`null`) for customer tokens (customers aren't a
  member of any Organization) and for `platform_admin` tokens (acting across
  every tenant). Code that legitimately needs a tenant on the request calls
  `TenantContext.require()`, which throws a clear error instead of silently
  proceeding with `null` if it's ever wired up to the wrong kind of token.

The same shape repeats for `AppUser`: `tenant_id` is nullable, `NULL` for
customers and platform admins, set for operator staff.

## The marketplace note

Customers browse and book across **every** operator on the platform, so
their read paths are intentionally cross-tenant:

- `RouteRepository.findAllByOriginAndDestination` - no tenant filter, this is
  the "any operator, city A to city B" search. `idx_routes_search` backs it.
- `TripRepository.findAllByRouteIdAndDepartureAtAfter` - same idea, one level
  down.

Staff-facing endpoints for managing one operator's own routes/buses/trips use
the tenant-scoped finders instead (`findAllByTenantId`,
`findByIdAndTenantId`). `GET/POST/PATCH/DELETE /api/fleet/{buses,routes,trips}(/{id})`
cover full CRUD as of 2026-08-23 (originally just create + list). `PATCH`
only applies non-null fields from the request; a trip's `routeId`/`busId`
aren't editable that way since seats are generated from the bus's
capacity/layout once, at creation. `DELETE` **soft-deactivates, never
deletes the row** - buses/routes got a new `active` column for this
(`V2__fleet_active_flag.sql`, `PATCH {"active": true}` reactivates); trips
reuse the existing `status` column instead. No cascading effect on either -
deactivating doesn't touch existing bookings on that route/trip. `GET
/api/trips/{tripId}/seats` is the seat map for one trip - previously there
was no way to discover a seat's id through the API at all. `GET
/api/trips/search` also takes `page`/`size` (default `0`/`20`, capped at
100) with an `X-Total-Count` response header - previously returned every
matching trip unbounded, and the cap is still applied in-memory after the
full result set is assembled, not pushed into the DB query.

## Pricing note

v1 prices per trip, flat, regardless of seat (`trips.price`,
`Trip.getPrice()`). `seats.seat_class` already exists in the schema and is
populated (`standard` by default) but unused for pricing - it's there so
per-seat-class pricing can be added later (e.g. a `seat_class -> multiplier`
table) without a schema migration, just a change to how `BookingWriter`
computes `total_amount`.

## Booking flow

`BookingService` implements: select seat(s) -> acquire a short-lived Redis
lock per seat (`SeatLockService`) -> lock acquired (write the booking) or
already locked (409 `SeatConflictException`). The lock only proves *this
request* currently holds the seat in Redis; `BookingWriter`'s DB write
re-checks `seats.status = 'open'` inside the transaction, so a seat sold
through some other path can't be double-sold either.

The DB write lives in `BookingWriter`, a separate bean from `BookingService`,
so its `@Transactional` goes through Spring's proxy correctly - calling a
`@Transactional` method on `this` from inside the same class silently skips
the transaction. `CancellationService` and `CurrentUserService.provision`
follow the same split for the same reason.

Idempotency: a `(tenant_id, idempotency_key)` unique constraint on
`bookings` means a retried request with the same key returns the original
booking rather than re-locking seats or double-booking - checked before any
locking happens.

`channel` is `self_service` (customer BFF, JWT role `customer`, no org) or
`counter` (agent BFF, JWT role `agent`, org set - and enforced to match the
trip's own operator via `TenantMismatchException`).

Every request resolves its Keycloak subject to an internal `app_user.id` via
`CurrentUserService.resolveInternalUserId`, provisioning the row on first
login (Keycloak stays the source of truth for identity; `app_user` is a
local mirror we can join against - `bookings.customer_user_id`,
`cancellations.cancelled_by`, notification recipient lookups, etc.).

## Refund & cancellation module

Two cancellation endpoints (both `CancellationController`), kept separate
rather than one path branching on role because their booking lookups are
scoped completely differently:

- `POST /api/bookings/{bookingId}/cancel` (`AGENT` or `OPERATOR_ADMIN`) -
  the original staff path. Looks the booking up tenant-scoped (an agent
  can only cancel their own operator's bookings), computes the refund,
  flips the booking to `cancelled`, frees its seats back to `open`, records
  a `cancellations` row, and writes a `booking_cancelled` outbox
  notification to the customer on the booking.
- `POST /api/my-bookings/{bookingId}/cancel` (`CUSTOMER`, added
  2026-08-23) - customer self-service. Looks the booking up
  ownership-scoped by `customerUserId` instead, since a customer token
  carries no tenant. `GET /api/my-bookings` and `GET
  /api/my-bookings/{bookingId}` (also `CUSTOMER`) are its list/detail
  companions.

Both cancel endpoints call into the same `CancellationService`: `cancel`
and `cancelAsCustomer` each resolve (and access-check) their own booking,
then hand off to a shared `applyCancellation` that computes the refund,
flips the booking to `cancelled`, frees its seats, records a
`cancellations` row, and writes the outbox notification - all inside one
`@Transactional` method, the same shape as `BookingWriter.write`.
- **Refund policy** (`refund_policies`): each operator configures an ordered
  list of tiers keyed by hours-before-departure, e.g.
  ```json
  [
    {"cutoff_hours": 24, "refund_percent": 100},
    {"cutoff_hours": 2,  "refund_percent": 50},
    {"cutoff_hours": 0,  "refund_percent": 0}
  ]
  ```
  meaning: 24h+ notice refunds in full, 2-24h refunds half, under 2h refunds
  nothing. `route_id NULL` is the operator-wide default; a row with a
  specific `route_id` overrides it for that route only.
  `RefundCalculator` picks the operator's route-specific policy if one
  exists, falls back to the operator-wide default, sorts the tiers
  highest-cutoff-first (defensively - don't rely on insertion order), and
  applies the first tier whose cutoff the booking's notice period clears.
  **If the operator hasn't configured any policy yet, the refund is zero**
  rather than failing the cancellation outright - a missing policy is a
  config gap, not grounds to block a cancellation.
  `GET/POST/PATCH/DELETE /api/fleet/refund-policies(/{id})` (added
  2026-08-23) is how those tiers get configured now, instead of hand-run
  SQL - `DELETE` is a real delete here (safe: no policy just means 0%
  refund, per above), unlike buses/routes/trips which only soft-deactivate.
- `payments` exists in the schema (cash today, a real gateway later) and
  now has a full CRUD API (`GET/POST/PATCH /api/bookings/{id}/payments`,
  added 2026-08-23, no `DELETE` since a payment is a financial fact) - but
  it's still not *automatically* wired into the booking/cancellation flow;
  recording one is a deliberate separate staff action.

## Platform administration

`platform_admin` was, until 2026-08-23, fully wired through auth but had
zero endpoints checking for it - onboarding a new operator meant hand-running
`infra/keycloak/create-demo-org.sh` and the SQL insert it prints.
`GET/POST/PATCH/DELETE /api/platform/operators(/{id})` (`PLATFORM_ADMIN`,
`PlatformController`, full CRUD as of 2026-08-23) is now that surface.
`POST` creates the Keycloak Organization via the Admin REST API first (the
same two calls `create-demo-org.sh` makes by hand), then inserts the local
`operators` row. `PATCH` only allows editing `name` - the org alias is
fixed at creation, changing it would break tenant resolution for every
existing staff login at that operator. `DELETE` soft-deactivates via the
`status` column operators already had (no migration needed there), same
"no cascading effect" caveat as fleet deactivation above. Config lives
under `bustix.keycloak-admin.*` (`KEYCLOAK_ADMIN_BASE_URL`/`KEYCLOAK_ADMIN`/
`KEYCLOAK_ADMIN_PASSWORD` - the latter two reuse the same env vars Keycloak's
own container is already seeded with).

## Frontend

`node-bff/frontend/` is a React + Vite + Tailwind SPA (added 2026-08-24) -
its own `package.json`/lockfile/build lifecycle, not an npm workspace of
`node-bff`. `node-bff/Dockerfile` is now multi-stage: a `frontend-build`
stage builds it, and the output is copied into the runtime stage as
`./public`, served via `express.static` with an `index.html` fallback for
client-side routes (falling back further to the pre-frontend stub when no
build is present, so backend-only local dev is unaffected). No nginx
changes needed. Local dev: `npm run dev` in `frontend/` (Vite on `:5173`,
proxying `/api`/`/auth` to node-bff on `:3000`) - log in once at
`:3000/auth/login` (Keycloak's redirect URI is hardcoded there), then work
against `:5173` for hot reload.

Only the customer flow (search → seats → book → confirm → cancel → My
Bookings) is built so far - agent, operator_admin fleet management, and
platform_admin operator onboarding have no UI yet. See CLAUDE.md's
"Frontend" section for the design system, the two `spring-boot-api`
additions this needed (`GET /api/trips/{tripId}`,
`GET /api/my-bookings/{bookingId}/seats`), and two real bugs found and
fixed along the way in `node-bff`'s API proxy: every no-body write request
was getting forwarded with the wrong Content-Type, turning into a spurious
403; and an expired refresh token (a tab left open past Keycloak's refresh
token lifetime, not just the access token's) surfaced as an opaque 500
instead of a clean login redirect. Neither is frontend-only - both affected
the whole BFF proxy layer. Trip search was also fixed to be
case-insensitive (`RouteRepository`/`TripController` in `spring-boot-api`)
- see CLAUDE.md for detail on all three.

## Auth / BFF details

- Keycloak realm `bustix`, four realm roles: `platform_admin`,
  `operator_admin`, `agent`, `customer` (`realm-export.json`).
- `bus-ticketing-bff` is a confidential client with PKCE
  (`pkce.code.challenge.method: S256`), redirect URI
  `http://localhost:3000/auth/callback`. **Its client secret is
  auto-generated by Keycloak on import** (the realm export doesn't pin one) -
  see ".env.example" for how to fetch the real value; the
  `BFF_CLIENT_SECRET=changeme-in-keycloak-console` default in
  `docker-compose.yml` will not authenticate as-is.
- `node-bff` does OIDC discovery against `KEYCLOAK_ISSUER` (the internal
  `http://keycloak:8080/...` URL, reachable container-to-container) but
  rewrites `authorization_endpoint`/`end_session_endpoint` to
  `KEYCLOAK_ISSUER_PUBLIC` (`http://localhost:8080/...`) before handing URLs
  to the browser, since the browser can't resolve the `keycloak` hostname.
  See the comment at the top of `node-bff/src/auth/oidc.js` for the full
  reasoning, including why `issuer.metadata.issuer` itself is *not*
  rewritten.
- Sessions are stored in Redis (`connect-redis`), not memory, so the BFF can
  restart or scale to more than one instance without silently logging
  everyone out.
- `bustix.tenant.org-claim-path` in `application.yml` names the token claim
  `TenantContextFilter` reads for the org id. **Verify this against a real
  token** after logging in - Keycloak's Organizations claim shape can differ
  by version. Decode a token at jwt.io or use the Admin Console's "Evaluate"
  tab, then adjust that one property (not any code) if it differs.

## Running it

```bash
docker compose up --build
```

First boot takes a couple of minutes (Keycloak's `start-dev` + realm import
is slow cold). `node-bff` retries its OIDC discovery call with backoff for
exactly this reason, so it doesn't need to win a race with Keycloak - see
`discoverWithRetry` in `node-bff/src/auth/oidc.js`.

Then, one-time setup:

1. **Create the demo operator's Keycloak Organization and grab its id:**
   ```bash
   ./infra/keycloak/create-demo-org.sh
   ```
   It prints the org id and the `INSERT INTO operators (...)` statement to
   run against `bus_ticketing` - run that insert (e.g. via `docker compose
   exec postgres psql -U bustix -d bus_ticketing`).
2. **Fetch the BFF client secret** from the admin console
   (`http://localhost:8080` -> `bustix` realm -> Clients ->
   `bus-ticketing-bff` -> Credentials) and put it in `.env` as
   `BFF_CLIENT_SECRET`, then `docker compose up -d --force-recreate
   node-bff`.
3. **(Optional) seed a refund policy** for the demo operator so
   cancellations refund something other than zero:
   ```sql
   INSERT INTO refund_policies (tenant_id, route_id, rules) VALUES (
     '<the operator id from step 1>',
     NULL,
     '[{"cutoff_hours":24,"refund_percent":100},{"cutoff_hours":2,"refund_percent":50},{"cutoff_hours":0,"refund_percent":0}]'
   );
   ```

Then log in at `http://localhost:3000/auth/login` as `demo-operator-admin`
or `demo-customer` (both `changeme`, both forced to reset on first login -
see `realm-export.json`).

Service URLs:

| Service | URL |
|---|---|
| App (via nginx) | http://localhost |
| node-bff directly | http://localhost:3000 |
| Keycloak admin console | http://localhost:8080 |
| spring-boot-api (for debugging) | http://localhost:8081 |

## Known gaps

- `payments` has a full CRUD API (see above) but isn't *automatically*
  wired into the booking/cancellation flow - recording one is a deliberate
  separate staff action.
- Email is a stub (`LoggingEmailSender` just logs); swap in a real
  `NotificationSender` when ready - the outbox table, retry, and
  status-tracking machinery around it doesn't need to change.
- Controller/integration tests exist (Testcontainers-based) but haven't
  been run and confirmed green on every dev machine yet - see CLAUDE.md's
  "Known gaps" section for the current state and why.
- The frontend (see above) only has a customer flow - agent, operator_admin,
  and platform_admin have no UI yet, despite their APIs being complete.
