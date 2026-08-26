# Cargo & Logistics Module — v1 Scope

Scoping session 2026-08-25, against `ethiopian_bus_system_specs.md` section 3
("Cargo & Logistics Module") and section 4.2 ("Prohibited Items"). Decisions
below were confirmed with the user before any code was written, same
practice as every prior phase in `CLAUDE.md`. This doc is the source of
truth for that implementation pass, not a permanent record once merged -
fold its outcome into `CLAUDE.md` the way every other phase has been.

## Decisions confirmed with the user

1. **Who creates a waybill**: staff only (`AGENT`/`OPERATOR_ADMIN`,
   tenant-scoped), mirroring the counter-booking channel — a parcel is
   physically weighed/inspected at a terminal counter. Customers get a
   **read-only track** path, not a create path (open question below on its
   exact shape).
2. **Trip linkage**: `waybill.trip_id` is **required**, mirroring
   `bookings.trip_id`. `dispatched_at`/`arrived_at` correlate with that
   trip's own `departure_at`/`arrival_at`, but are **not** auto-derived —
   see point 5.
3. **Freight pricing**: **per-route flat rate**, operator-configurable.
   Modeled as a new `cargo_rates` table shaped exactly like
   `refund_policies` (`tenant_id`, `route_id` **nullable** = operator-wide
   default, overridden by a route-specific row) — reuses a mental model
   already established rather than inventing a second one. Unlike
   `RefundCalculator`'s "no policy = 0% refund" fallback, **no configured
   rate blocks waybill creation** (400) rather than defaulting to a free
   shipment — the zero-default only makes sense when zero is the safe
   answer, and a free freight charge isn't.
4. **Prohibited items**: platform-wide config property
   (`bustix.cargo.prohibited-items`, a YAML list), same mechanism as
   `bustix.ticketing.vat-rate` — editable without a redeploy, no CRUD API,
   no per-operator table. Checked against `description` at waybill creation
   (case-insensitive substring match against each configured term; the
   BRD's "regex-supported" is satisfied by allowing a term itself to be a
   regex fragment, not by requiring the whole list to be regexes).
5. **Status transitions**: **manual staff action at each step** — a
   dedicated endpoint per transition (`dispatch`/`arrive`/`collect`), same
   shape as `BoardingService.checkIn`, not inferred from the linked trip's
   timestamps. A trip's own arrival isn't reliably known in real time
   (`TripLifecycleScheduler` only ever flips *trip* status off a clock, and
   only for departure) — cargo physically changing hands needs a human
   confirming it, same reasoning as boarding.
6. **Payment**: a `payment_status` enum column
   (`UNPAID`/`PAID`/`COLLECT_ON_DELIVERY`) directly on the waybill, set via
   `PATCH`. **Not** wired into the existing `payments` table — narrowest
   cut, and consistent with "Known gaps" already noting payments aren't
   auto-wired into the booking flow either.
7. **Accompanied baggage**: `waybill.booking_id` is an **optional** FK from
   v1 — an agent can attach excess baggage to an existing passenger's
   booking at the same counter interaction. No change to the passenger
   booking flow or its frontend; this is purely an optional pointer on the
   cargo side.
8. **Cancellation/refund**: reuses `RefundCalculator` as-is — it's already
   generic (`calculate(tenantId, routeId, totalAmount, departureAt)`, not
   booking-specific), so a waybill's cancellation resolves the refund off
   its trip's `routeId`/`departureAt` and its own `total_cargo_cost`,
   against the **same `refund_policies`** an operator already configures
   for passenger bookings. Only allowed pre-dispatch (post-dispatch cargo
   is physically on a moving bus — cancellation stops making sense the same
   way it does for a departed trip). A new `cargo_waybill_cancellations`
   audit table mirrors `cancellations`/`booking_reschedules` rather than
   overloading the booking-shaped `cancellations` table.
9. **Customer tracking auth model**: **public, unauthenticated,
   two-factor** — `GET /api/cargo/track/{waybillNumber}?phone=...`, no
   session required. `waybill_number` alone isn't treated as sufficient
   proof of a right to view a shipment (it's not a secret, and consignors
   often aren't registered platform customers at all, so the existing
   ownership-scoped `findByIdAndCustomerUserId` pattern can't apply here) —
   the caller must also supply a phone number matching **either**
   `consignor_phone` or `consignee_phone` on that waybill, mismatch or
   unknown number both 404 identically (same "exists but not yours reads
   as doesn't exist" rule the rest of the API already follows). The
   response is deliberately narrow: `waybillNumber`, `status`,
   `issued_at`/`dispatched_at`/`arrived_at`/`collected_at`, and the trip's
   origin/destination/departure — **not** `declared_value`,
   `total_cargo_cost`, `payment_status`, or either party's
   name/ID-number, since this path has no login and no tenant/ownership
   check beyond the phone match. A logged-in customer's own richer view
   (if ever added) would be a separate, later concern.
10. **Excess-weight threshold**: kept **uniform** across accompanied
    baggage, standalone parcels, and commercial freight (no
    `waybill_type` to branch on — same reasoning as before), but the `30`
    itself is **operator-configurable**, not hardcoded:
    `cargo_rates.free_weight_threshold_kg` (default `30.00`, matching the
    BRD's own number), edited through the same `/api/fleet/cargo-rates`
    CRUD as `base_freight_charge`/`surcharge_per_kg`/`handling_fee` — one
    more field on a row that's already operator/route-configurable, not a
    new mechanism. `excess_weight_kg` is computed at waybill-creation time
    as `GREATEST(gross_weight_kg - rate.free_weight_threshold_kg, 0)`
    using whichever rate resolved for that route at that moment, then
    **snapshotted** onto the waybill row (same principle as `trips.price`
    being copied onto a booking) — a later change to the operator's
    threshold must never retroactively change an already-issued waybill's
    charges.
11. **Post-dispatch immutability**: the physical-shipment fields
    (`description`, `quantity`, `declared_value`, `gross_weight_kg`,
    `consignor_*`/`consignee_*`) are only `PATCH`-editable while
    `status = 'issued'` — once `dispatched`, they freeze, mirroring the
    "Immutability Principle" already applied to a booking's passenger
    name/ID post-issuance (section 5.3). `payment_status` is exempt from
    this freeze (payment can legitimately happen or get corrected at any
    point in the shipment's life, same as it can for a booking today).
    Editing a frozen field returns 409, not a silent no-op — same style as
    `TooLateToRescheduleException`/`BookingAlreadyCancelledException`
    rather than `PATCH`'s usual "only non-null fields are applied" being
    stretched to also mean "and some fields are quietly ignored."

## Schema (draft `V7__cargo_logistics.sql`)

```sql
CREATE TABLE cargo_rates (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES operators(id),
    route_id                UUID REFERENCES routes(id), -- NULL = operator-wide default
    free_weight_threshold_kg NUMERIC(5,2) NOT NULL DEFAULT 30.00,
    base_freight_charge     NUMERIC(10,2) NOT NULL,
    surcharge_per_kg        NUMERIC(10,2) NOT NULL DEFAULT 10.00,
    handling_fee            NUMERIC(10,2) NOT NULL DEFAULT 50.00,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cargo_rates_tenant ON cargo_rates(tenant_id);

CREATE TABLE cargo_waybills (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES operators(id),
    trip_id                UUID NOT NULL REFERENCES trips(id),
    booking_id             UUID REFERENCES bookings(id), -- optional: accompanied excess baggage
    waybill_number         VARCHAR(32) NOT NULL UNIQUE,

    consignor_name         VARCHAR(255) NOT NULL,
    consignor_phone        VARCHAR(20) NOT NULL,
    consignor_id_number    VARCHAR(64),
    consignee_name         VARCHAR(255) NOT NULL,
    consignee_phone        VARCHAR(20) NOT NULL,
    consignee_id_number    VARCHAR(64),

    description            TEXT NOT NULL,
    quantity                INTEGER NOT NULL DEFAULT 1,
    declared_value         NUMERIC(10,2),
    gross_weight_kg        NUMERIC(6,2) NOT NULL,
    excess_weight_kg       NUMERIC(6,2) NOT NULL, -- GREATEST(gross_weight_kg - 30, 0), computed at write time

    base_freight_charge    NUMERIC(10,2) NOT NULL,
    weight_surcharge       NUMERIC(10,2) NOT NULL,
    handling_service_fee   NUMERIC(10,2) NOT NULL,
    total_cargo_cost       NUMERIC(10,2) NOT NULL,
    payment_status         VARCHAR(20) NOT NULL DEFAULT 'unpaid',

    status                 VARCHAR(20) NOT NULL DEFAULT 'issued', -- issued|dispatched|arrived|collected|cancelled
    issued_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at          TIMESTAMPTZ,
    arrived_at             TIMESTAMPTZ,
    collected_at           TIMESTAMPTZ,
    consignee_id_verified  BOOLEAN NOT NULL DEFAULT false,

    issued_by              UUID REFERENCES app_user(id)
);
CREATE INDEX idx_cargo_waybills_tenant ON cargo_waybills(tenant_id);
CREATE INDEX idx_cargo_waybills_trip ON cargo_waybills(trip_id);
-- Track-by-number is the customer-facing lookup path (see open question below).
CREATE UNIQUE INDEX idx_cargo_waybills_number ON cargo_waybills(waybill_number);

CREATE TABLE cargo_waybill_cancellations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    waybill_id      UUID NOT NULL REFERENCES cargo_waybills(id),
    cancelled_by    UUID REFERENCES app_user(id),
    reason          VARCHAR(255),
    refund_amount   NUMERIC(10,2) NOT NULL,
    refunded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Open point on the schema: `quantity`/`declared_value`/`description` are
per-waybill, single-item, matching the BRD's flat `item_metrics` — a
waybill covering *multiple distinct items* in one shipment (each with its
own weight) isn't modeled; treating a waybill as one homogenous shipment
lot, same granularity the BRD itself uses. Flag if that's wrong.

## New package: `com.bustix.cargo`

Mirrors `com.bustix.boarding`'s shape (small, service + controller):

- `CargoRate`, `CargoRateRepository` — operator/route rate config.
- `CargoWaybill`, `CargoWaybillRepository`.
- `CargoWaybillCancellation`, `CargoWaybillCancellationRepository`.
- `CreateWaybillRequest`, `UpdateWaybillRequest`, `CollectWaybillRequest`.
- `WaybillNumberGenerator` — mirrors `TicketNumberGenerator`
  (operator-initials + year + sequence, e.g. `DBC-CARGO-2026-4763827`),
  same bounded-retry-on-collision pattern.
- `ProhibitedItemException` (400), `NoCargoRateConfiguredException` (400),
  `IdentityMismatchException` reused or a cargo-specific twin (409) for the
  collect-time consignee ID check.
- `CargoWaybillService` — pricing calc, prohibited-items check,
  `WaybillWriter`-style separate `@Transactional` bean for the create path
  (same reason `BookingWriter` is split out — proxying).
- `CargoWaybillController` — staff endpoints under `/api/cargo`.
- `CargoRateController` — `/api/fleet/cargo-rates`, same
  `OPERATOR_ADMIN`-only CRUD shape as `RefundPolicyController`.

## Endpoints (draft)

Staff (`AGENT`/`OPERATOR_ADMIN`, tenant-scoped):
- `POST /api/cargo/waybills` — create.
- `GET /api/cargo/waybills` — list (filter by `status`, `tripId`).
- `GET /api/cargo/waybills/{id}`.
- `PATCH /api/cargo/waybills/{id}` — corrections + `payment_status`;
  physical-shipment fields 409 once `status != 'issued'` (decision 11).
- `POST /api/cargo/waybills/{id}/dispatch`.
- `POST /api/cargo/waybills/{id}/arrive`.
- `POST /api/cargo/waybills/{id}/collect` — `{presentedIdNumber}`.
- `POST /api/cargo/waybills/{id}/cancel` — pre-dispatch only.

Operator admin (fleet-shaped config CRUD):
- `GET/POST/PATCH/DELETE /api/fleet/cargo-rates(/{id})`.

Customer-facing (public, no session — decision 9):
- `GET /api/cargo/track/{waybillNumber}?phone=...` — narrow status-only
  view, 404 on a non-matching phone (see decision 9 for the exact field
  list and the "no login" reasoning).

## Explicitly out of scope for this pass

- No notification-outbox wiring for cargo status changes (no
  `cargo_dispatched`/`cargo_arrived` emails) — the existing outbox pattern
  would support it later without schema change, same as email being a
  stub today.
- No customer-initiated waybill creation (see decision 1).
- No multi-item-per-waybill modeling (see schema note above).
- No integration with the existing `payments` table/`PaymentController`
  (see decision 6).
- Frontend: not scoped yet at all — this doc is backend-schema/API scope
  only. A follow-up pass would need at minimum a staff `pages/cargo/`
  (create waybill, list/search, per-waybill dispatch/arrive/collect
  actions) mirroring the `pages/operator/`/`pages/agent/` shape, plus
  whatever the tracking-page decision above ends up being.
