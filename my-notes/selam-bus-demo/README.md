# Selam Bus Line — demo operator seed

Created 2026-08-27 for a demo to company management. A full second tenant
alongside "Demo Bus Co", with realistic Ethiopian inter-city routes, fleet,
staff users, ~6 weeks of bookings/revenue history and a cargo book.

## Identity

- **Operator** `operators.id = 617eedf4-67c7-4766-a8c3-9dae7b89fc6e`
  name `Selam Bus Line`, `keycloak_org_id = selam-bus` (alias), tin `0040283719`
  (reuses a stub row that already existed; the Keycloak org `selam-bus`
  / id `be8f1a11-8243-4578-84ff-7c24cdf6f0c3` also already existed)
- **Keycloak users** (realm `bustix`, all password `changeme123`, non-temporary,
  members of the `selam-bus` organization):
  | username | role | name | keycloak id | app_user.id |
  |---|---|---|---|---|
  | `selam-admin`  | operator_admin | Meseret Alemu | 27501488-db85-4f9c-8149-f46f61550531 | a1000000-0000-4000-8000-000000000001 |
  | `selam-agent1` | agent | Dawit Bekele | 9aa495ac-b725-4301-824b-61ea5321dfe3 | a1000000-0000-4000-8000-000000000002 |
  | `selam-agent2` | agent | Hanna Girma  | 4ae93efe-e93b-4fe5-bfd2-09d60d7fc876 | a1000000-0000-4000-8000-000000000003 |

Log in at http://localhost/auth/login . The realm uses a username-first
flow (type username → Sign In → password → Sign In).

## Seeded data (all tenant-scoped to the operator id above)

- 4 buses (2x2, capacity 44–51), 12 routes (6 city pairs each direction:
  Addis Ababa ↔ Bahir Dar / Gondar / Mekelle / Hawassa / Dessie / Dire Dawa),
  operator-wide refund policy (100/50/0 % at 48/12/0 h) and cargo rate
  (base 150, 25 kg free, 8/kg surcharge, 40 handling).
- 78 trips, dep. −21 d … +15 d from seed time (30 upcoming = `scheduled`,
  rest `boarding_closed`); seats auto-generated per bus.
- 48 bookings (40 confirmed / 8 cancelled) across self_service / counter /
  guest, 1–4 seats, Ethiopian passenger names, spread over the last ~5 weeks
  by `created_at` so the operator dashboard trend/KPI/top-routes/occupancy
  panels all have real numbers (~ETB 96k confirmed revenue).
- 31 payments (cash / telebirr / cbe_birr / card).
- 6 cargo waybills (issued / dispatched / arrived / collected + one
  customer-`requested` from `demo-customer`), multi-item, priced by the
  same formula the app uses.

## Re-running

`gen_selam_seed.py` is deterministic (fixed RNG seed). It regenerates
`selam_seed.sql`; the SQL is **not idempotent** (would duplicate buses/
trips/bookings) — only re-run against a DB where this operator has no
fleet/booking rows yet. The Keycloak users + org are created separately via
the admin REST API (see the session transcript / CLAUDE.md's
`create-demo-org.sh` for the shape).

```
python gen_selam_seed.py > selam_seed.sql
psql -h localhost -U bustix -d bus_ticketing -v ON_ERROR_STOP=1 -f selam_seed.sql
```
