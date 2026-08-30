# Operator role partitioning — planning doc

**Status:** draft, 2026-08-30. To be refined as new features land (each new
capability adds rows to the role/capability matrix below). Not implemented.

## Problem

Within one bus operator, staff access is a binary: `operator_admin` (can do
everything) vs `agent` (bookings + counter ops + cargo, via a couple of
widened endpoints). Real operators have distinct jobs — a fleet scheduler,
a cashier/finance person, a cargo clerk, a counter agent — that today all
collapse into one of those two buckets. We want to split the "what can you
do within your operator" axis without touching the tenancy axis.

## Two axes, keep them separate

| Axis | Mechanism | Status |
|---|---|---|
| **Tenant isolation** — *which* operator's data you can touch | `organization` claim → `TenantContextFilter` → `operators.id` | Done, solid (see CLAUDE.md "Tenancy model", `TenantIsolationIntegrationTest`) |
| **Task partitioning** — *what* you can do within your operator | realm roles + `@PreAuthorize` | This doc |

Role partitioning does **not** interact with tenant resolution. A
`operator_fleet` user at operator A still only ever sees A's buses — the
tenant scope already applies to every staff-scoped repository method. The
two are composable and independent.

## Recommended model: ~5 fixed position-roles, composed in Keycloak

Do **not** jump to per-endpoint permissions. That's the right model only if
operators need to define their own custom roles (a product decision not yet
made — see Open decisions). Start with a small fixed set of realm roles:

| Role | Owns | Endpoints (as of 2026-08-30) |
|---|---|---|
| `operator_admin` | everything + **staff management** | all of the below, plus assigning roles to their own org's members |
| `operator_fleet` | buses, routes, trips, boarding gate / dispatch | `GET/POST/PATCH/DELETE /api/fleet/{buses,routes,trips}`, `POST /api/bookings/{id}/seats/{seatId}/check-in` |
| `operator_finance` | refund policies, cargo rates, operator settings, payments, analytics | `/api/fleet/{refund-policies,cargo-rates}`, `/api/fleet/settings`, `/api/bookings/{id}/payments`, `/api/cargo/waybills/{id}/payments`, `GET /api/operator/dashboard` |
| `operator_agent` | bookings: create / cancel / reschedule, counter ops | `POST /api/bookings`, `/api/bookings/{id}/{cancel,reschedule}`, `GET /api/bookings(/{id})`, `GET /api/agent/dashboard` |
| `operator_cargo` | waybill lifecycle, confirm customer requests | `/api/cargo/waybills/**` (dispatch/arrive/collect/cancel/confirm-and-issue), `GET /api/cargo/requests` |

Notes / edges to resolve as we refine:

- **`operator_admin` is a Keycloak composite role** containing all four
  granular roles. This single fact is what makes the migration safe: every
  existing admin token automatically carries the granular roles, so nothing
  breaks while `@PreAuthorize` is migrated controller-by-controller.
- **Overlaps:** `GET /api/fleet/{trips,routes}` list endpoints are already
  `hasAnyRole('OPERATOR_ADMIN','AGENT')` (the cargo trip-picker needs
  them). Under the new model they'd be readable by fleet + agent + cargo +
  finance — i.e. "any operator staff". Consider a synthetic
  `operator_staff` base role that every position composes, for
  "any-authenticated-operator-staff" reads.
- **Dispatch / trip status vs. fleet CRUD:** folded into `operator_fleet`
  for now. Split into `operator_dispatcher` later if a real person needs
  trip-status / boarding control without route/bus edit rights.
- **Read-only:** no `operator_viewer` yet. Add when someone needs
  dashboards without any write capability.

## How it's modeled in Keycloak

- **Realm roles**, not client roles — the `JwtAuthenticationConverter`
  already reads `realm_access.roles` and maps to `ROLE_*` authorities.
  Consistent with the existing 4 realm roles.
- **Groups as the assignment unit:** one Keycloak group per position
  (`/operators/fleet`, `/operators/finance`, `/operators/agent`,
  `/operators/cargo`, `/operators/admin`) with the composite role mapped.
  Assigning a staff member to a group = giving them that position.
  Bulk-manageable, and it's exactly what the in-app Staff screen toggles.
- **Token stays the only source of truth for authz.** Same rule as
  `AppUser.tenant_id` being a mirror — a display copy in Postgres is fine,
  enforcement reads the token. Composite roles flatten into
  `realm_access.roles`, so ~5–8 extra strings per token, negligible.
- The `organization` claim (tenant) and the position roles are fully
  independent: a user is a member of exactly one Organization and holds N
  position roles within it.

## Backend migration (incremental, non-breaking)

Migrate `@PreAuthorize` one controller at a time:

```java
// before
@PreAuthorize("hasRole('OPERATOR_ADMIN')")
// after
@PreAuthorize("hasAnyRole('OPERATOR_ADMIN', 'OPERATOR_FLEET')")
```

Because `operator_admin` composes the granular roles, admins never lose
access mid-migration — ship it controller-by-controller.

- `AbstractIntegrationTest` gets `asFleetManager(...)` / `asCashier(...)` /
  `asCargoClerk(...)` builders alongside `asOperatorAdmin(...)`.
- `TenantIsolationIntegrationTest` gains cases proving a fleet manager
  can't hit `/api/fleet/settings`, a cargo clerk can't touch fleet, etc.
- **Signal to add a permission layer:** if `@PreAuthorize` expressions
  start listing 3+ roles routinely
  (`hasAnyRole('OPERATOR_ADMIN','OPERATOR_FLEET','OPERATOR_FINANCE',...)`),
  introduce `@PreAuthorize("hasAuthority('perm:pricing')")` backed by a
  converter that expands roles → permissions. Not before.

## Frontend change

The multi-role case from the 2026-08-30 nav review becomes real — a user
will hold several roles. Move from role-block concatenation in `AppShell`
to a **capability-driven nav**: compute a capability set from `roles`,
render each nav section by capability, not by `hasRole('operator_admin')`.

- `RequireRole` gains a `permissions` / `anyOf` mode.
- The Settings hub tabs map cleanly: General/Contact →
  `operator_finance` + `operator_admin`; Refund Policies / Cargo Rates →
  `operator_finance`.
- Dedupe: the "Dashboard" link and any section a user reaches via two
  roles must render once, with a divider between distinct groups.

## New feature this unlocks: an in-app Staff screen

`operator_admin` needs to assign positions to their own org's members
without the Keycloak admin console.

- New `operator_admin`-only `/api/operator/staff` controller: list the
  caller's Organization members, toggle their group memberships.
- Extend `KeycloakOrganizationClient` (already creates orgs) with member
  list + group assignment via the Keycloak Admin REST API. Same "Keycloak
  is identity truth" pattern as `OperatorProvisioningService`.
- Frontend: a "Staff" tab in the Settings hub (or its own nav entry),
  gated on an `operator_admin`-only `staff:manage` capability.

## Rollout phases

0. **Now** — this doc. Lock the role vocabulary. No code.
1. Make `operator_admin` a Keycloak composite of the (not-yet-created)
   granular roles. Add the granular realm roles + groups. Existing behaviour
   unchanged (admin composite = same effective access).
2. Assign real staff to granular groups. Add test auth builders.
3. Migrate `@PreAuthorize` per controller to `hasAnyRole(admin, <granular>)`.
   Admins unaffected throughout.
4. Frontend: capability-driven nav + `RequireRole` permissions mode.
5. In-app Staff screen (`/api/operator/staff` + Settings tab).
6. (Only if needed) permission-authority layer for custom roles.

## Open decisions (revisit as features expand)

1. **Role granularity** — is the 5-role split right, or split further
   (`operator_dispatcher`, `operator_viewer`)? Coarser is easier to live
   with; add roles when a real person needs a narrower slice.
2. **Custom roles?** — will operators ever define their own
   role/permission bundles? Yes → build the permission layer from phase 1.
   No → fixed composites are enough.
3. **Assignment UX** — Keycloak admin console only (fine for pilot) vs the
   in-app Staff screen (needed before non-technical operator admins
   onboard their own staff).
4. **`operator_staff` base role** for "any operator staff can read this"
   endpoints (fleet/route lists, trip search) — worth adding, or keep
   enumerating roles?
5. **`agent` realm role** — keep as an alias for `operator_agent`, or
   rename for consistency (`operator_*` prefix across the board)? Renaming
   touches `SecurityConfig`, the JWT converter, every `@PreAuthorize`,
   `realm-export.json`, and existing Keycloak users — probably keep `agent`
   as-is and just add the `operator_agent` composite pointing at the same
   capabilities.

## References

- CLAUDE.md — "Tenancy model", "Auth / BFF details", "Platform administration"
- `infra/keycloak/realm-export.json` — the 4 current realm roles
- `spring-boot-api/.../config/SecurityConfig.java` — `jwtAuthenticationConverter`
- `spring-boot-api/.../platform/KeycloakOrganizationClient.java` — Admin API client to extend
- `my-notes/cargo_logistics_scope_v1.md` — example of a scope doc refined before build
