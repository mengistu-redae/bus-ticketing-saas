# Ethiopian bus-operator demo roster

Created 2026-08-30. Twelve real, well-known Ethiopian intercity bus operators
seeded as full tenants alongside the existing "Demo Bus Co" and "Selam Bus
Line", so the marketplace, operator dashboards and cross-operator search all
have a realistic multi-operator dataset.

Operator names, hub cities and route corridors are real. TINs, plate numbers,
staff people, phone numbers, prices, brand colours and all booking / cargo
data are synthetic.

## The roster

| Operator | org alias | hub | staff usernames |
|---|---|---|---|
| Sky Bus Transport System | `sky-bus` | Addis Ababa | `skybus-admin` `skybus-agent1` `skybus-agent2` |
| Golden Bus Transport | `golden-bus` | Addis Ababa | `goldenbus-admin` `goldenbus-agent1/2` |
| Abay Bus | `abay-bus` | Bahir Dar | `abaybus-admin` `abaybus-agent1/2` |
| Habesha Bus | `habesha-bus` | Addis Ababa | `habeshabus-admin` `habeshabus-agent1/2` |
| Ethio Bus | `ethio-bus` | Addis Ababa | `ethiobus-admin` `ethiobus-agent1/2` |
| Zemen Bus | `zemen-bus` | Addis Ababa | `zemenbus-admin` `zemenbus-agent1/2` |
| Liyu Bus | `liyu-bus` | Addis Ababa | `liyubus-admin` `liyubus-agent1/2` |
| Walia Bus Transport | `walia-bus` | Addis Ababa | `waliabus-admin` `waliabus-agent1/2` |
| Getbus | `getbus` | Hawassa | `getbus-admin` `getbus-agent1/2` |
| Oda Bus Transport | `oda-bus` | Adama | `odabus-admin` `odabus-agent1/2` |
| Alsam Bus | `alsam-bus` | Addis Ababa | `alsambus-admin` `alsambus-agent1/2` |
| Yebeza Bus | `yebeza-bus` | Addis Ababa | `yebezabus-admin` `yebezabus-agent1/2` |

- **All 36 staff** (12 × operator_admin + 24 × agent) are Keycloak realm
  `bustix` users, password `changeme123` (non-temporary), members of their
  operator's Keycloak Organization, with the matching realm role.
  `<slug>-admin` = operator_admin, `<slug>-agent1/2` = agent.
- `zemen-bus` reused the pre-existing empty stub operator row + Keycloak org;
  the other 11 orgs + `operators` rows were created fresh.
- Exact ids (operator id, org id, keycloak user id, app_user id) are in
  `_provision.json`.

Log in at http://localhost/auth/login — username-first flow (type username →
Sign In → password → Sign In).

## Seeded data per operator

- 4 buses (2x2 capacity 44–51, one 2x3 ≈ 60), 10–14 routes, operator-wide
  refund policy (100 / 40–60 / 0 % at varying cutoffs) and cargo rate,
  branding (display name, tagline, brand + accent colour) and support
  contact info in `operator_settings`.
- 65–91 trips, departures −21 d … +15 d from seed time (25–35 upcoming =
  `scheduled`, rest `boarding_closed`); seats auto-generated per bus layout.
  Trips run outbound from the hub, and for the non-Addis hubs (Abay / Getbus
  / Oda) also on the Addis-corridor leg, so every operator sells inventory a
  customer searching from Addis will see.
- ~42 bookings (≈ 36 confirmed / 6 cancelled) across self_service / counter /
  guest, 1–4 seats, Ethiopian passenger names, spread over the last ~5 weeks
  by `created_at` so operator-dashboard trend / KPI / top-route / occupancy
  panels all have real numbers (confirmed revenue ≈ ETB 30k–170k / operator).
- Payments (cash / telebirr / cbe_birr / card) on ~55–85 % of confirmed
  bookings.
- 6 cargo waybills each (issued / dispatched / arrived / collected + one
  customer-`requested` from `demo-customer`), multi-item, priced by the same
  formula the app uses.

self_service bookings are all attributed to the existing `customer demo`
app_user (there is no separate per-operator customer account); guest bookings
carry a synthetic contact phone.

## Files

| file | what |
|---|---|
| `operators_def.py` | static roster: names, cities, corridors, colours, staff, bus/route derivation |
| `provision.py` | **idempotent** — Keycloak orgs + users + `operators` / `app_user` rows; writes `_provision.json` |
| `gen_seed.py` | reads `_provision.json` → `seed_data.sql` (fleet / trips / bookings / cargo). **Not idempotent** — self-guards and aborts if these operators already have fleet rows |
| `cleanup_data.sql` | deletes all generated *data* for the 12 operators (keeps operators / app_user / operator_settings) so the seed can be re-applied |
| `run.sh` | runs all three steps in order |
| `_provision.json` | generated id map (operator / org / keycloak / app_user ids) |
| `seed_data.sql` | generated seed (regenerate, don't hand-edit) |

## Running / re-running

```bash
cd my-notes/ethiopian-operators-demo
./run.sh                     # provision.py + gen_seed.py + apply

# to re-seed the DATA only (e.g. after editing gen_seed.py):
export PATH="$PATH:/c/Program Files/PostgreSQL/17/bin"; export PGPASSWORD=bustix
psql -h localhost -U bustix -d bus_ticketing -f cleanup_data.sql
python gen_seed.py > seed_data.sql
psql -h localhost -U bustix -d bus_ticketing -v ON_ERROR_STOP=1 -f seed_data.sql
```

`provision.py` needs the Keycloak admin API (`admin`/`admin` on
`localhost:8080`) and `psql` on PATH. It re-logs-in automatically as the
admin-cli token expires mid-run.

## Verified 2026-08-30

- `provision.py` created 12 orgs + 36 users; `operators` = 14 rows total.
- Seed applied clean; every operator 4 buses / 10–14 routes / 65–91 trips /
  6 waybills; `seats.status='booked'` count == confirmed `booking_seats`
  count (no double-sell).
- Marketplace `GET /api/trips/search` returns multiple operators per lane
  (Addis↔Bahir Dar: 6 operators; Addis↔Hawassa incl. Getbus; Adama→Dire Dawa:
  Oda) with `branding` embedded.
- Live password-grant tokens (temporary directAccessGrants flip, reverted)
  for `skybus-admin` / `getbus-admin` / `zemenbus-admin` / `odabus-agent1`:
  correct `organization` claim, `GET /api/operator/dashboard` 200 with real
  aggregates, agent/operator role gates enforced, and `skybus-admin` gets
  404 on a `getbus` trip id (tenant isolation holds).
