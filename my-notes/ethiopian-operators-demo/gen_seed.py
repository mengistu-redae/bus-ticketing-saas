#!/usr/bin/env python3
"""Generate the data seed (fleet / trips / bookings / cargo) for every operator
in _provision.json. Emits one transactional .sql file to stdout.

Deterministic per operator (RNG seeded from the operator slug). The SQL is
NOT idempotent (would duplicate buses/trips/bookings) - it opens with a guard
that aborts if any of these operators already has fleet rows. Run provision.py
first; run this once.

    python gen_seed.py > seed_data.sql
    psql -v ON_ERROR_STOP=1 -f seed_data.sql
"""
import json
import os
import random
import sys
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

from operators_def import OPERATORS, buses_for, routes_for

HERE = os.path.dirname(os.path.abspath(__file__))
NOW = datetime.now(timezone.utc).replace(microsecond=0)
VAT = Decimal("0.15")
DEMO_CUSTOMER_APP_USER = "01471b88-ef40-4a35-8b0d-2e9c46e1c422"  # existing 'customer demo'

with open(os.path.join(HERE, "_provision.json")) as f:
    PROV = json.load(f)

FIRST = ["Abebe", "Almaz", "Bekele", "Chaltu", "Dawit", "Eyerusalem", "Fikadu", "Genet",
         "Hana", "Ibrahim", "Kalkidan", "Lelise", "Mekonnen", "Nardos", "Obang", "Rahel",
         "Samuel", "Tigist", "Wondimu", "Yohannes", "Zerihun", "Marta", "Getachew", "Selamawit",
         "Biruk", "Meron", "Tesfaye", "Aster", "Kidus", "Bethlehem", "Firaol", "Saron",
         "Amanuel", "Hirut", "Robel", "Nahom", "Semir", "Eyob", "Lidya", "Henok"]
LAST = ["Alemu", "Bekele", "Girma", "Haile", "Kebede", "Lemma", "Mengistu", "Negash",
        "Tadesse", "Wolde", "Yimer", "Assefa", "Desta", "Fantahun", "Gebre", "Tola",
        "Worku", "Solomon", "Ahmed", "Tariku"]
ID_TYPES = ["KEBELE_ID", "DIGITAL_ID", "PASSPORT", "DRIVERS_LICENSE"]
PAY_METHODS = ["cash", "telebirr", "cbe_birr", "card"]
CARGO_DESCS = [
    "Assorted textiles - wholesale garments", "Injera flour (teff) sacks",
    "Spare vehicle parts - alternators and belts", "Packaged coffee beans for export prep",
    "Household electronics - 2 televisions", "Medical supplies - non-refrigerated",
    "Books and stationery cartons", "Dried spices - berbere and shiro",
]
COLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

out = []
def emit(line=""):
    out.append(line)

# global across all operators (ticket_number / booking_ref uniqueness is
# platform-wide in the app, and generated ids must not clash between operators)
USED_TICKETS, USED_REFS, USED_WAYBILLS = set(), set(), set()

def money(x):
    return Decimal(x).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

def q(s):
    return "NULL" if s is None else "'" + str(s).replace("'", "''") + "'"

def ts(dt):
    return "'" + dt.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S+00") + "'"

def seat_numbers(capacity, layout):
    a, b = layout.lower().split("x")
    per = int(a) + int(b)
    seats, row = [], 1
    while len(seats) < capacity:
        for c in range(per):
            if len(seats) >= capacity:
                break
            seats.append(f"{row}{COLS[c]}")
        row += 1
    return seats


class Gen:
    def __init__(self, op, prov):
        self.op = op
        self.prov = prov
        self.oid = prov["operator_id"]
        self.R = random.Random((hash(op["slug"]) ^ 0xB0A7) & 0xFFFFFFFF)
        self.prefix = "".join(w[0] for w in op["name"].split())[:4].upper()
        self.used_tickets, self.used_refs, self.used_waybills = USED_TICKETS, USED_REFS, USED_WAYBILLS
        self.agents = [s["app_user_id"] for s in prov["staff"] if s["role"] == "agent"]
        self.admin = next(s["app_user_id"] for s in prov["staff"] if s["role"] == "operator_admin")

    def rid(self):
        return str(uuid.UUID(int=self.R.getrandbits(128)))

    def person(self):
        return f"{self.R.choice(FIRST)} {self.R.choice(LAST)}"

    def phone(self):
        return "+251" + self.R.choice("79") + "".join(str(self.R.randint(0, 9)) for _ in range(8))

    def ticket_number(self):
        while True:
            c = f"{self.prefix}-2026-{self.R.randint(0, 9_999_999):07d}"
            if c not in self.used_tickets:
                self.used_tickets.add(c); return c

    def booking_ref(self):
        while True:
            c = uuid.UUID(int=self.R.getrandbits(128)).hex[:6].upper()
            if c not in self.used_refs:
                self.used_refs.add(c); return c

    def waybill_number(self):
        while True:
            c = f"{self.prefix}-CARGO-2026-{self.R.randint(0, 9_999_999):07d}"
            if c not in self.used_waybills:
                self.used_waybills.add(c); return c

    # ---------------------------------------------------------------- emit
    def run(self):
        op, R, oid = self.op, self.R, self.oid
        emit(f"\n-- ===== {op['name']}  ({op['alias']} / {oid}) =====")

        # branding + contact (operator_settings singleton row)
        emit(
            f"INSERT INTO operator_settings (tenant_id, display_name, tagline, brand_color, accent_color, "
            f"support_phone, support_email, support_address, website_url, ticket_footer_note) VALUES "
            f"({q(oid)}, {q(op['short'])}, {q(op['tagline'])}, {q(op['brand'])}, {q(op['accent'])}, "
            f"{q(self.phone())}, {q('info@' + op['domain'])}, {q(op['hub'] + ', Ethiopia')}, "
            f"{q('https://www.' + op['domain'])}, {q('Thank you for travelling with ' + op['short'] + '.')}) "
            f"ON CONFLICT (tenant_id) DO UPDATE SET "
            f"display_name = EXCLUDED.display_name, tagline = EXCLUDED.tagline, "
            f"brand_color = EXCLUDED.brand_color, accent_color = EXCLUDED.accent_color, "
            f"support_phone = EXCLUDED.support_phone, support_email = EXCLUDED.support_email, "
            f"support_address = EXCLUDED.support_address, website_url = EXCLUDED.website_url, "
            f"ticket_footer_note = EXCLUDED.ticket_footer_note, updated_at = now();")

        # operator-wide refund policy (slight per-operator variation)
        h1 = R.choice([24, 36, 48]); h2 = R.choice([6, 12]); p2 = R.choice([40, 50, 60])
        emit(f"INSERT INTO refund_policies (id, tenant_id, route_id, rules) VALUES "
             f"({q(self.rid())}, {q(oid)}, NULL, "
             f"'[{{\"cutoff_hours\": {h1}, \"refund_percent\": 100}}, "
             f"{{\"cutoff_hours\": {h2}, \"refund_percent\": {p2}}}, "
             f"{{\"cutoff_hours\": 0, \"refund_percent\": 0}}]'::jsonb);")

        # operator-wide cargo rate
        c_free = Decimal(str(R.choice([20, 25, 30])))
        c_base = Decimal(str(R.choice([120, 150, 180, 200])))
        c_sur = Decimal(str(R.choice([6, 8, 10, 12])))
        c_hand = Decimal(str(R.choice([30, 40, 50])))
        emit(f"INSERT INTO cargo_rates (id, tenant_id, route_id, free_weight_threshold_kg, "
             f"base_freight_charge, surcharge_per_kg, handling_fee) VALUES "
             f"({q(self.rid())}, {q(oid)}, NULL, {c_free}, {c_base}, {c_sur}, {c_hand});")
        self.cargo_rate = (c_free, c_base, c_sur, c_hand)

        # buses
        buses = []
        for plate, cap, layout in buses_for(op):
            bid = self.rid()
            buses.append((bid, cap, layout))
            created = NOW - timedelta(days=R.randint(120, 500))
            emit(f"INSERT INTO buses (id, tenant_id, plate_no, capacity, seat_layout, active, created_at) "
                 f"VALUES ({q(bid)}, {q(oid)}, {q(plate)}, {cap}, {q(layout)}, true, {ts(created)});")

        # routes (both directions); trips only run on the "outbound from hub" ones
        route_ids = []
        for (o_, d_, km, ot, dt_, price) in routes_for(op):
            rid_ = self.rid()
            created = NOW - timedelta(days=R.randint(120, 500))
            emit(f"INSERT INTO routes (id, tenant_id, origin, destination, distance_km, active, "
                 f"origin_terminal, destination_terminal, created_at) VALUES "
                 f"({q(rid_)}, {q(oid)}, {q(o_)}, {q(d_)}, {km}, true, {q(ot)}, {q(dt_)}, {ts(created)});")
            # run scheduled trips outbound from the operator's hub, and (for
            # operators hubbed outside the capital) also on the Addis->hub leg
            # so every operator sells inventory on the busy Addis corridor
            if o_ == op["hub"] or o_ == "Addis Ababa":
                route_ids.append((rid_, d_, Decimal(price), km))

        # trips + seats
        trips = []
        bus_cycle = 0
        for (route_id, dest, price, km) in route_ids:
            kmh = R.choice([50, 52, 55, 58])
            dur_h = max(1.5, km / kmh)
            day = -21
            while day <= 15:
                local_hour = 5 if (day // 3) % 2 == 0 else 14
                dep = (NOW + timedelta(days=day)).replace(hour=(local_hour - 3) % 24, minute=30, second=0)
                if local_hour - 3 < 0:
                    dep -= timedelta(days=1)
                arr = dep + timedelta(hours=dur_h)
                bid, cap, layout = buses[bus_cycle % len(buses)]
                bus_cycle += 1
                status = "scheduled" if dep > NOW else "boarding_closed"
                tid = self.rid()
                created = dep - timedelta(days=R.randint(20, 45))
                if created > NOW:
                    created = NOW - timedelta(days=R.randint(1, 20))
                seat_rows = [(self.rid(), sn) for sn in seat_numbers(cap, layout)]
                trips.append(dict(id=tid, dest=dest, price=price, dep=dep, status=status, seats=seat_rows))
                emit(f"INSERT INTO trips (id, tenant_id, route_id, bus_id, departure_at, arrival_at, "
                     f"price, status, created_at) VALUES ({q(tid)}, {q(oid)}, {q(route_id)}, {q(bid)}, "
                     f"{ts(dep)}, {ts(arr)}, {price}.00, {q(status)}, {ts(created)});")
                vals = ",".join(f"({q(sid)}, {q(tid)}, {q(sn)}, 'standard', 'open')" for sid, sn in seat_rows)
                emit(f"INSERT INTO seats (id, trip_id, seat_no, seat_class, status) VALUES {vals};")
                day += 3

        self._bookings(trips)
        self._cargo(trips)

    def _bookings(self, trips):
        R, oid = self.R, self.oid
        trips.sort(key=lambda t: t["dep"])
        future = [t for t in trips if t["dep"] > NOW]
        past = [t for t in trips if t["dep"] <= NOW]
        seat_taken = {t["id"]: set() for t in trips}

        def free_seat(t):
            for sid, sn in t["seats"]:
                if sid not in seat_taken[t["id"]]:
                    return sid, sn
            return None

        specs = []
        for _ in range(42):
            pool = past[-16:] + future + future
            if not pool:
                break
            t = R.choice(pool)
            earliest = t["dep"] - timedelta(days=14)
            latest = min(t["dep"], NOW)
            if earliest >= latest:
                earliest = latest - timedelta(days=3)
            created = earliest + timedelta(seconds=R.randint(0, int((latest - earliest).total_seconds())))
            specs.append((created, t))
        specs.sort(key=lambda x: x[0])

        for created, t in specs:
            ch = R.choices(["self_service", "counter", "guest"], weights=[46, 38, 16])[0]
            nseats = R.choices([1, 2, 3, 4], weights=[52, 30, 12, 6])[0]
            picked = []
            for _ in range(nseats):
                fs = free_seat(t)
                if not fs:
                    break
                seat_taken[t["id"]].add(fs[0])
                picked.append(fs)
            if not picked:
                continue
            cancelled = R.random() < 0.13
            status = "cancelled" if cancelled else "confirmed"
            if cancelled:
                for sid, _ in picked:
                    seat_taken[t["id"]].discard(sid)

            bid = self.rid()
            price = t["price"]
            subtotal = money(price * len(picked))
            tax = money(subtotal * VAT)
            total = money(subtotal + tax)
            cust = agent = guest_phone = "NULL"
            if ch == "self_service":
                cust = q(DEMO_CUSTOMER_APP_USER)
            elif ch == "counter":
                agent = q(R.choice(self.agents))
            else:
                guest_phone = q(self.phone())

            emit(f"INSERT INTO bookings (id, tenant_id, trip_id, customer_user_id, agent_user_id, channel, "
                 f"status, idempotency_key, total_amount, subtotal_amount, tax_amount, reschedule_fee, "
                 f"ticket_number, booking_ref, guest_contact_phone, created_at) VALUES "
                 f"({q(bid)}, {q(oid)}, {q(t['id'])}, {cust}, {agent}, {q(ch)}, {q(status)}, "
                 f"{q(self.rid())}, {total}, {subtotal}, {tax}, 0, {q(self.ticket_number())}, "
                 f"{q(self.booking_ref())}, {guest_phone}, {ts(created)});")

            for sid, sn in picked:
                pname = self.person()
                pphone = self.phone() if R.random() < 0.8 else None
                pidnum = pidtype = None
                if ch == "counter" and R.random() < 0.7:
                    pidnum = f"ETH{R.randint(100000, 999999)}"
                    pidtype = R.choice(ID_TYPES)
                page = R.choice([None, None, None, 19, 24, 28, 31, 37, 45, 52, 60])
                emit(f"INSERT INTO booking_seats (booking_id, seat_id, price, passenger_name, "
                     f"passenger_phone, passenger_id_number, passenger_id_type, passenger_age, "
                     f"boarding_status) VALUES ({q(bid)}, {q(sid)}, {price}.00, {q(pname)}, {q(pphone)}, "
                     f"{q(pidnum)}, {q(pidtype)}, {page if page is not None else 'NULL'}, 'not_boarded');")
                if status == "confirmed":
                    emit(f"UPDATE seats SET status = 'booked' WHERE id = {q(sid)};")

            if status == "confirmed":
                pay = {"counter": 0.85, "self_service": 0.55, "guest": 0.4}[ch]
                if R.random() < pay:
                    method = R.choice(PAY_METHODS)
                    txn = None
                    if method in ("telebirr", "cbe_birr"):
                        txn = method.upper().replace("_", "") + str(R.randint(10**9, 10**10 - 1))
                    collected_by = q(R.choice(self.agents)) if ch == "counter" else "NULL"
                    collected_at = created + timedelta(minutes=R.randint(1, 30))
                    emit(f"INSERT INTO payments (id, booking_id, method, amount, collected_by, "
                         f"collected_at, transaction_id) VALUES ({q(self.rid())}, {q(bid)}, {q(method)}, "
                         f"{total}, {collected_by}, {ts(collected_at)}, {q(txn)});")

    def _cargo(self, trips):
        R, oid = self.R, self.oid
        c_free, c_base, c_sur, c_hand = self.cargo_rate
        pool = [t for t in trips if t["dep"] > NOW - timedelta(days=5)]
        if not pool:
            return

        def pricing(weight):
            excess = max(Decimal("0"), Decimal(str(weight)) - c_free)
            sur = money(excess * c_sur)
            return excess.quantize(Decimal("0.01")), c_base, sur, c_hand, money(c_base + sur + c_hand)

        for st in ["issued", "issued", "dispatched", "arrived", "collected"]:
            t = R.choice(pool)
            wid = self.rid()
            items, tw, tv = [], Decimal("0"), Decimal("0")
            for _ in range(R.choice([1, 1, 2])):
                d = R.choice(CARGO_DESCS)
                w = money(R.choice([8, 12, 15, 18, 22, 28, 35, 45]))
                v = money(R.choice([500, 1200, 2500, 4000, 8000]))
                items.append((d, R.randint(1, 6), v, w))
                tw += w; tv += v
            excess, base, sur, hand, total = pricing(tw)
            created = NOW - timedelta(days=R.randint(1, 6), hours=R.randint(0, 12))
            order = ["issued", "dispatched", "arrived", "collected"]
            idx = order.index(st)
            disp = arr = coll = "NULL"
            verified = "false"
            if idx >= 1:
                disp = ts(created + timedelta(hours=R.randint(2, 10)))
            if idx >= 2:
                arr = ts(created + timedelta(hours=R.randint(12, 30)))
            if idx >= 3:
                coll = ts(created + timedelta(hours=R.randint(31, 50)))
                verified = "true"
            pay_status = "paid" if st in ("arrived", "collected") else R.choice(["unpaid", "unpaid", "paid"])
            emit(f"INSERT INTO cargo_waybills (id, tenant_id, trip_id, booking_id, waybill_number, "
                 f"consignor_name, consignor_phone, consignor_id_number, consignee_name, consignee_phone, "
                 f"consignee_id_number, description, declared_value, gross_weight_kg, excess_weight_kg, "
                 f"base_freight_charge, weight_surcharge, handling_service_fee, total_cargo_cost, "
                 f"payment_status, status, dispatched_at, arrived_at, collected_at, consignee_id_verified, "
                 f"issued_by, customer_user_id, created_at) VALUES "
                 f"({q(wid)}, {q(oid)}, {q(t['id'])}, NULL, {q(self.waybill_number())}, "
                 f"{q(self.person())}, {q(self.phone())}, "
                 f"{q('ETH' + str(R.randint(100000, 999999)) if R.random() < 0.6 else None)}, "
                 f"{q(self.person())}, {q(self.phone())}, {q('ETH' + str(R.randint(100000, 999999)))}, "
                 f"{q(items[0][0])}, {money(tv)}, {money(tw)}, {excess}, {base}, {sur}, {hand}, {total}, "
                 f"{q(pay_status)}, {q(st)}, {disp}, {arr}, {coll}, {verified}, {q(self.agents[0])}, "
                 f"NULL, {ts(created)});")
            for d, qty, v, w in items:
                emit(f"INSERT INTO cargo_waybill_items (id, waybill_id, description, quantity, "
                     f"declared_value, gross_weight_kg) VALUES ({q(self.rid())}, {q(wid)}, {q(d)}, "
                     f"{qty}, {v}, {w});")
            if pay_status == "paid":
                method = R.choice(["cash", "telebirr", "cbe_birr"])
                txn = (method.upper().replace("_", "") + str(R.randint(10**9, 10**10 - 1))) \
                    if method != "cash" else None
                emit(f"INSERT INTO payments (id, waybill_id, method, amount, collected_by, collected_at, "
                     f"transaction_id) VALUES ({q(self.rid())}, {q(wid)}, {q(method)}, {total}, "
                     f"{q(self.agents[0])}, {ts(created + timedelta(hours=1))}, {q(txn)});")

        # one customer-initiated 'requested' waybill (no trip yet)
        wid = self.rid()
        created = NOW - timedelta(days=1, hours=4)
        emit(f"INSERT INTO cargo_waybills (id, tenant_id, trip_id, booking_id, waybill_number, "
             f"consignor_name, consignor_phone, consignor_id_number, consignee_name, consignee_phone, "
             f"consignee_id_number, description, declared_value, gross_weight_kg, payment_status, status, "
             f"consignee_id_verified, issued_by, customer_user_id, created_at) VALUES "
             f"({q(wid)}, {q(oid)}, NULL, NULL, {q(self.waybill_number())}, {q('customer demo')}, "
             f"{q(self.phone())}, NULL, {q(self.person())}, {q(self.phone())}, "
             f"{q('ETH' + str(R.randint(100000, 999999)))}, "
             f"{q('Personal effects - 2 boxes clothing and shoes')}, 3000.00, 19.00, 'unpaid', "
             f"'requested', false, NULL, {q(DEMO_CUSTOMER_APP_USER)}, {ts(created)});")
        emit(f"INSERT INTO cargo_waybill_items (id, waybill_id, description, quantity, declared_value, "
             f"gross_weight_kg) VALUES ({q(self.rid())}, {q(wid)}, {q('Clothing - box 1')}, 1, 1500.00, "
             f"10.00), ({q(self.rid())}, {q(wid)}, {q('Shoes - box 2')}, 1, 1500.00, 9.00);")


# ------------------------------------------------------------------- main
emit("-- ===================================================================")
emit(f"-- Ethiopian bus-operator demo: data seed (generated {NOW:%Y-%m-%d %H:%M:%S} UTC)")
emit(f"-- {len(OPERATORS)} operators - NOT idempotent, run once")
emit("-- ===================================================================")
emit("BEGIN;")
aliases = ",".join(q(op["alias"]) for op in OPERATORS)
emit(f"""
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM buses b JOIN operators o ON o.id = b.tenant_id
    WHERE o.keycloak_org_id IN ({aliases});
  IF n > 0 THEN
    RAISE EXCEPTION 'demo fleet already seeded for these operators (% bus rows) - aborting', n;
  END IF;
END $$;""")

for op in OPERATORS:
    prov = PROV.get(op["alias"])
    if not prov:
        sys.exit(f"missing {op['alias']} in _provision.json - run provision.py first")
    Gen(op, prov).run()

emit("\nCOMMIT;")
emit()
emit("-- summary")
emit(f"""SELECT o.name,
  (SELECT count(*) FROM buses  WHERE tenant_id = o.id) buses,
  (SELECT count(*) FROM routes WHERE tenant_id = o.id) routes,
  (SELECT count(*) FROM trips  WHERE tenant_id = o.id) trips,
  (SELECT count(*) FROM trips  WHERE tenant_id = o.id AND status = 'scheduled') upcoming,
  (SELECT count(*) FROM bookings WHERE tenant_id = o.id AND status = 'confirmed') confirmed,
  (SELECT COALESCE(SUM(total_amount), 0) FROM bookings WHERE tenant_id = o.id AND status = 'confirmed') revenue,
  (SELECT count(*) FROM cargo_waybills WHERE tenant_id = o.id) waybills
FROM operators o WHERE o.keycloak_org_id IN ({aliases}) ORDER BY o.name;""")

sys.stdout.write("\n".join(out) + "\n")
