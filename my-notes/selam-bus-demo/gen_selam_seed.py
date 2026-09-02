#!/usr/bin/env python3
"""Generate a SQL seed script for the 'Selam Bus Line' operator demo.

Deterministic (fixed RNG seed). Emits one transactional .sql file to stdout.
Reuses the pre-existing operators row (Selam Bus / keycloak_org_id=selam-bus)
and the pre-existing Keycloak org + 3 users created via the admin API.
"""
import random
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

R = random.Random(20260827)
NOW = datetime.now(timezone.utc).replace(microsecond=0)

OPERATOR_ID = "617eedf4-67c7-4766-a8c3-9dae7b89fc6e"   # existing 'Selam Bus' row
OPERATOR_NAME = "Selam Bus Line"
TICKET_PREFIX = "SBL"   # first letter of each word, per TicketNumberGenerator
VAT = Decimal("0.15")

DEMO_CUSTOMER_APP_USER = "01471b88-ef40-4a35-8b0d-2e9c46e1c422"  # existing 'customer demo'

# Keycloak user ids created via admin API earlier this session
KC_ADMIN   = "27501488-db85-4f9c-8149-f46f61550531"
KC_AGENT1  = "9aa495ac-b725-4301-824b-61ea5321dfe3"
KC_AGENT2  = "4ae93efe-e93b-4fe5-bfd2-09d60d7fc876"

# fixed app_user ids for the 3 staff
AU_ADMIN  = "a1000000-0000-4000-8000-000000000001"
AU_AGENT1 = "a1000000-0000-4000-8000-000000000002"
AU_AGENT2 = "a1000000-0000-4000-8000-000000000003"

def money(x):
    return Decimal(x).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

def q(s):
    if s is None:
        return "NULL"
    return "'" + str(s).replace("'", "''") + "'"

def ts(dt):
    return "'" + dt.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S+00") + "'"

out = []
def emit(line=""):
    out.append(line)

# ---------------------------------------------------------------- uniqueness
used_tickets = set()
used_refs = set()
used_waybills = set()

def ticket_number():
    while True:
        c = f"{TICKET_PREFIX}-2026-{R.randint(0, 9_999_999):07d}"
        if c not in used_tickets:
            used_tickets.add(c); return c

def booking_ref():
    while True:
        c = uuid.UUID(int=R.getrandbits(128)).hex[:6].upper()
        if c not in used_refs:
            used_refs.add(c); return c

def waybill_number():
    while True:
        c = f"{TICKET_PREFIX}-CARGO-2026-{R.randint(0, 9_999_999):07d}"
        if c not in used_waybills:
            used_waybills.add(c); return c

def rid():
    return str(uuid.UUID(int=R.getrandbits(128)))

# ---------------------------------------------------------------- seat layout
COLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
def seat_numbers(capacity, layout):
    # mirrors SeatLayoutGenerator: "AxB" -> A+B seats/row
    a, b = layout.lower().split("x")
    per = int(a) + int(b)
    seats = []
    row = 1
    while len(seats) < capacity:
        for c in range(per):
            if len(seats) >= capacity:
                break
            seats.append(f"{row}{COLS[c]}")
        row += 1
    return seats

# ---------------------------------------------------------------- static data
FIRST = ["Abebe","Almaz","Bekele","Chaltu","Dawit","Eyerusalem","Fikadu","Genet",
         "Hana","Ibrahim","Kalkidan","Lelise","Mekonnen","Nardos","Obang","Rahel",
         "Samuel","Tigist","Wondimu","Yohannes","Zerihun","Marta","Getachew","Selamawit",
         "Biruk","Meron","Tesfaye","Aster","Kidus","Bethlehem"]
LAST = ["Alemu","Bekele","Girma","Haile","Kebede","Lemma","Mengistu","Negash",
        "Tadesse","Wolde","Yimer","Assefa","Desta","Fantahun","Gebre","Tola"]

def person():
    return f"{R.choice(FIRST)} {R.choice(LAST)}"

def phone():
    return "+2519" + "".join(str(R.randint(0, 9)) for _ in range(8))

ID_TYPES = ["KEBELE_ID", "DIGITAL_ID", "PASSPORT", "DRIVERS_LICENSE"]

BUSES = [
    ("3-45231 AA", 49, "2x2"),
    ("3-19874 AA", 49, "2x2"),
    ("3-52016 AA", 44, "2x2"),
    ("2-30945 ET", 51, "2x2"),
]

ROUTES = [
    # dest, distance_km, dest_terminal, price, avg_kmh
    ("Bahir Dar", 565.0, "Bahir Dar Bus Terminal", 1250, 55),
    ("Gondar",    727.0, "Gondar Bus Terminal",    1600, 55),
    ("Mekelle",   783.0, "Mekelle Bus Terminal",   1800, 52),
    ("Hawassa",   275.0, "Hawassa Bus Terminal",    720, 58),
    ("Dessie",    401.0, "Dessie Bus Terminal",     950, 52),
    ("Dire Dawa", 445.0, "Dire Dawa Bus Terminal", 1050, 55),
]
ORIGIN = "Addis Ababa"
ORIGIN_TERMINAL = "Meskel Square Bus Terminal"

emit("-- ===================================================================")
emit("-- Selam Bus Line demo seed  (generated %s UTC)" % NOW.strftime("%Y-%m-%d %H:%M:%S"))
emit("-- ===================================================================")
emit("BEGIN;")
emit()
emit("-- operator: reuse the existing stub row, give it a real name + TIN")
emit(f"UPDATE operators SET name = {q(OPERATOR_NAME)}, tin = '0040283719', status = 'active', updated_at = now() WHERE id = '{OPERATOR_ID}';")
emit()
emit("-- staff app_user rows (mirror the Keycloak users; provisioning would")
emit("-- otherwise create these on first login - pre-creating so seed FKs resolve)")
for au, kc, role, name, email in [
    (AU_ADMIN,  KC_ADMIN,  "operator_admin", "Meseret Alemu", "meseret.alemu@selambus.com"),
    (AU_AGENT1, KC_AGENT1, "agent",          "Dawit Bekele",  "dawit.bekele@selambus.com"),
    (AU_AGENT2, KC_AGENT2, "agent",          "Hanna Girma",   "hanna.girma@selambus.com"),
]:
    emit(f"INSERT INTO app_user (id, keycloak_user_id, tenant_id, role, display_name, email) "
         f"VALUES ('{au}', '{kc}', '{OPERATOR_ID}', {q(role)}, {q(name)}, {q(email)}) "
         f"ON CONFLICT (keycloak_user_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, role = EXCLUDED.role, display_name = EXCLUDED.display_name, email = EXCLUDED.email;")
emit()

# ---- buses
emit("-- buses")
bus_ids = []
for plate, cap, layout in BUSES:
    bid = rid(); bus_ids.append((bid, cap, layout))
    created = NOW - timedelta(days=R.randint(120, 400))
    emit(f"INSERT INTO buses (id, tenant_id, plate_no, capacity, seat_layout, active, created_at) "
         f"VALUES ('{bid}', '{OPERATOR_ID}', {q(plate)}, {cap}, {q(layout)}, true, {ts(created)});")
emit()

# ---- routes
emit("-- routes")
route_ids = []
for dest, dist, dterm, price, kmh in ROUTES:
    rid_ = rid()
    route_ids.append((rid_, dest, Decimal(price), kmh))
    created = NOW - timedelta(days=R.randint(120, 400))
    emit(f"INSERT INTO routes (id, tenant_id, origin, destination, distance_km, active, origin_terminal, destination_terminal, created_at) "
         f"VALUES ('{rid_}', '{OPERATOR_ID}', {q(ORIGIN)}, {q(dest)}, {dist}, true, {q(ORIGIN_TERMINAL)}, {q(dterm)}, {ts(created)});")
    emit(f"INSERT INTO routes (id, tenant_id, origin, destination, distance_km, active, origin_terminal, destination_terminal, created_at) "
         f"VALUES ('{rid()}', '{OPERATOR_ID}', {q(dest)}, {q(ORIGIN)}, {dist}, true, {q(dterm)}, {q(ORIGIN_TERMINAL)}, {ts(created)});")
emit()

# ---- refund policy (operator-wide)
emit("-- operator-wide refund policy")
emit(f"INSERT INTO refund_policies (id, tenant_id, route_id, rules) VALUES "
     f"('{rid()}', '{OPERATOR_ID}', NULL, "
     f"'[{{\"cutoff_hours\": 48, \"refund_percent\": 100}}, {{\"cutoff_hours\": 12, \"refund_percent\": 50}}, {{\"cutoff_hours\": 0, \"refund_percent\": 0}}]'::jsonb);")
emit()

# ---- cargo rate (operator-wide)
emit("-- operator-wide cargo rate")
CARGO_FREE = Decimal("25.00"); CARGO_BASE = Decimal("150.00"); CARGO_SUR = Decimal("8.00"); CARGO_HANDLING = Decimal("40.00")
emit(f"INSERT INTO cargo_rates (id, tenant_id, route_id, free_weight_threshold_kg, base_freight_charge, surcharge_per_kg, handling_fee) "
     f"VALUES ('{rid()}', '{OPERATOR_ID}', NULL, {CARGO_FREE}, {CARGO_BASE}, {CARGO_SUR}, {CARGO_HANDLING});")
emit()

# ---- trips + seats
emit("-- trips + seats")
trips = []   # (trip_id, route_tuple, bus_tuple, departure_dt, status, [seat rows])
bus_cycle = 0
for (route_id, dest, price, kmh) in route_ids:
    dist = next(d for d in ROUTES if d[0] == dest)[1]
    dur_h = dist / kmh
    # schedule: every 3 days from -21d to +15d, alternating 05:30 / 14:30 EAT
    day = -21
    while day <= 15:
        depart_local_hour = 5 if (day // 3) % 2 == 0 else 14
        depart_min = 30
        # EAT = UTC+3
        dep = (NOW + timedelta(days=day)).replace(hour=(depart_local_hour - 3) % 24, minute=depart_min, second=0)
        if depart_local_hour - 3 < 0:
            dep = dep - timedelta(days=1)
        arr = dep + timedelta(hours=dur_h)
        bid, cap, layout = bus_ids[bus_cycle % len(bus_ids)]
        bus_cycle += 1
        status = "scheduled" if dep > NOW else "boarding_closed"
        tid = rid()
        created = dep - timedelta(days=R.randint(20, 45))
        if created > NOW:
            created = NOW - timedelta(days=R.randint(1, 20))
        seats = seat_numbers(cap, layout)
        seat_rows = [(rid(), sn) for sn in seats]
        trips.append(dict(id=tid, route_id=route_id, dest=dest, price=price, bus=bid,
                          dep=dep, arr=arr, status=status, seats=seat_rows, cap=cap))
        emit(f"INSERT INTO trips (id, tenant_id, route_id, bus_id, departure_at, arrival_at, price, status, created_at) "
             f"VALUES ('{tid}', '{OPERATOR_ID}', '{route_id}', '{bid}', {ts(dep)}, {ts(arr)}, {price}.00, {q(status)}, {ts(created)});")
        # seats - bulk insert
        vals = ",".join(f"('{sid}', '{tid}', {q(sn)}, 'standard', 'open')" for sid, sn in seat_rows)
        emit(f"INSERT INTO seats (id, trip_id, seat_no, seat_class, status) VALUES {vals};")
        day += 3
emit()

# ---- bookings
emit("-- bookings + booking_seats + payments")
trips.sort(key=lambda t: t["dep"])
future_trips = [t for t in trips if t["dep"] > NOW]
past_trips   = [t for t in trips if t["dep"] <= NOW]

# track seat usage per trip
seat_taken = {t["id"]: set() for t in trips}

def free_seat(t):
    for sid, sn in t["seats"]:
        if sid not in seat_taken[t["id"]]:
            return sid, sn
    return None

PAY_METHODS = ["cash", "telebirr", "cbe_birr", "card"]
n_bookings = 48
booking_specs = []
for i in range(n_bookings):
    # bias toward recent past + upcoming departures (future weighted heavier
    # so occupancy on the operator dashboard's upcoming-departures panel is real)
    pool = past_trips[-20:] + future_trips + future_trips
    t = R.choice(pool)
    # created_at: between (dep - 14d) and min(dep, now); never future
    earliest = t["dep"] - timedelta(days=14)
    latest = min(t["dep"], NOW)
    if earliest >= latest:
        earliest = latest - timedelta(days=3)
    created = earliest + timedelta(seconds=R.randint(0, int((latest - earliest).total_seconds())))
    booking_specs.append((created, t))

booking_specs.sort(key=lambda x: x[0])

confirmed_count = 0
cancelled_count = 0
channel_tally = {}
for created, t in booking_specs:
    ch = R.choices(["self_service", "counter", "guest"], weights=[48, 37, 15])[0]
    channel_tally[ch] = channel_tally.get(ch, 0) + 1
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
    # status: mostly confirmed; some cancelled
    is_cancelled = R.random() < 0.14
    status = "cancelled" if is_cancelled else "confirmed"
    if is_cancelled:
        cancelled_count += 1
        # free the seats back
        for sid, sn in picked:
            seat_taken[t["id"]].discard(sid)
    else:
        confirmed_count += 1

    bid = rid()
    price = t["price"]
    subtotal = money(price * len(picked))
    tax = money(subtotal * VAT)
    total = money(subtotal + tax)
    tnum = ticket_number()
    ref = booking_ref()
    idem = str(uuid.UUID(int=R.getrandbits(128)))

    cust = "NULL"; agent = "NULL"; guest_phone = "NULL"
    if ch == "self_service":
        cust = f"'{DEMO_CUSTOMER_APP_USER}'"
    elif ch == "counter":
        agent = f"'{R.choice([AU_AGENT1, AU_AGENT2])}'"
    else:
        guest_phone = q(phone())

    emit(f"INSERT INTO bookings (id, tenant_id, trip_id, customer_user_id, agent_user_id, channel, status, "
         f"idempotency_key, total_amount, subtotal_amount, tax_amount, reschedule_fee, ticket_number, booking_ref, "
         f"guest_contact_phone, created_at) VALUES "
         f"('{bid}', '{OPERATOR_ID}', '{t['id']}', {cust}, {agent}, {q(ch)}, {q(status)}, {q(idem)}, "
         f"{total}, {subtotal}, {tax}, 0, {q(tnum)}, {q(ref)}, {guest_phone}, {ts(created)});")

    # seat status: booked only if confirmed AND trip is future (so occupancy shows);
    # for past confirmed trips also mark booked (historical realism, harmless)
    for sid, sn in picked:
        pname = person()
        pphone = phone() if R.random() < 0.8 else None
        pidnum = None; pidtype = None
        if ch == "counter" and R.random() < 0.7:
            pidnum = f"ETH{R.randint(100000, 999999)}"
            pidtype = R.choice(ID_TYPES)
        page = R.choice([None, None, 24, 31, 45, 28, 52, 19, 37, 60])
        emit(f"INSERT INTO booking_seats (booking_id, seat_id, price, passenger_name, passenger_phone, "
             f"passenger_id_number, passenger_id_type, passenger_age, boarding_status) VALUES "
             f"('{bid}', '{sid}', {price}.00, {q(pname)}, {q(pphone)}, {q(pidnum)}, {q(pidtype)}, "
             f"{page if page is not None else 'NULL'}, 'not_boarded');")
        if status == "confirmed":
            emit(f"UPDATE seats SET status = 'booked' WHERE id = '{sid}';")

    # payment (only for confirmed)
    if status == "confirmed":
        pay = False; collected_by = "NULL"
        if ch == "counter":
            pay = R.random() < 0.85
            collected_by = agent
        elif ch == "self_service":
            pay = R.random() < 0.55
        else:
            pay = R.random() < 0.4
        if pay:
            method = R.choice(PAY_METHODS)
            txn = None
            if method in ("telebirr", "cbe_birr"):
                txn = method.upper().replace("_", "") + str(R.randint(10**9, 10**10 - 1))
            collected_at = created + timedelta(minutes=R.randint(1, 30))
            emit(f"INSERT INTO payments (id, booking_id, method, amount, collected_by, collected_at, transaction_id) "
                 f"VALUES ('{rid()}', '{bid}', {q(method)}, {total}, {collected_by}, {ts(collected_at)}, {q(txn)});")
emit()

# ---- cargo waybills
emit("-- cargo waybills + items")
CARGO_DESCS = [
    ("Assorted textiles - wholesale garments", 1),
    ("Injera flour (teff) sacks", 4),
    ("Spare vehicle parts - alternators and belts", 2),
    ("Packaged coffee beans for export prep", 6),
    ("Household electronics - 2 televisions", 2),
    ("Medical supplies - non-refrigerated", 3),
    ("Books and stationery cartons", 5),
]

def cargo_pricing(weight):
    excess = max(Decimal("0"), Decimal(str(weight)) - CARGO_FREE)
    surcharge = money(excess * CARGO_SUR)
    total = money(CARGO_BASE + surcharge + CARGO_HANDLING)
    return excess.quantize(Decimal("0.01")), CARGO_BASE, surcharge, CARGO_HANDLING, total

cargo_trips = [t for t in trips if t["dep"] > NOW - timedelta(days=5)]
statuses = ["issued", "issued", "dispatched", "arrived", "collected"]
for st in statuses:
    t = R.choice(cargo_trips)
    wid = rid()
    wnum = waybill_number()
    nitems = R.choice([1, 1, 2])
    items = []
    tw = Decimal("0")
    tv = Decimal("0")
    for _ in range(nitems):
        d, qty = R.choice(CARGO_DESCS)
        w = money(R.choice([8, 12, 15, 18, 22, 28, 35, 45]))
        v = money(R.choice([500, 1200, 2500, 4000, 8000]))
        items.append((d, qty, v, w))
        tw += w; tv += v
    excess, base, sur, hand, total = cargo_pricing(tw)
    created = NOW - timedelta(days=R.randint(1, 6), hours=R.randint(0, 12))
    consignor = person(); consignee = person()
    consignee_id = f"ETH{R.randint(100000,999999)}"
    consignor_id = f"ETH{R.randint(100000,999999)}" if R.random() < 0.6 else None
    dispatched_at = arrived_at = collected_at = "NULL"
    verified = "false"
    order = ["issued", "dispatched", "arrived", "collected"]
    idx = order.index(st)
    if idx >= 1: dispatched_at = ts(created + timedelta(hours=R.randint(2, 10)))
    if idx >= 2: arrived_at = ts(created + timedelta(hours=R.randint(12, 30)))
    if idx >= 3:
        collected_at = ts(created + timedelta(hours=R.randint(31, 50)))
        verified = "true"
    pay_status = "paid" if st in ("arrived", "collected") else R.choice(["unpaid", "unpaid", "paid"])
    emit(f"INSERT INTO cargo_waybills (id, tenant_id, trip_id, booking_id, waybill_number, "
         f"consignor_name, consignor_phone, consignor_id_number, consignee_name, consignee_phone, consignee_id_number, "
         f"description, declared_value, gross_weight_kg, excess_weight_kg, base_freight_charge, weight_surcharge, "
         f"handling_service_fee, total_cargo_cost, payment_status, status, dispatched_at, arrived_at, collected_at, "
         f"consignee_id_verified, issued_by, customer_user_id, created_at) VALUES "
         f"('{wid}', '{OPERATOR_ID}', '{t['id']}', NULL, {q(wnum)}, "
         f"{q(consignor)}, {q(phone())}, {q(consignor_id)}, {q(consignee)}, {q(phone())}, {q(consignee_id)}, "
         f"{q(items[0][0])}, {money(tv)}, {money(tw)}, {excess}, {base}, {sur}, {hand}, {total}, "
         f"{q(pay_status)}, {q(st)}, {dispatched_at}, {arrived_at}, {collected_at}, {verified}, "
         f"'{AU_AGENT1}', NULL, {ts(created)});")
    for d, qty, v, w in items:
        emit(f"INSERT INTO cargo_waybill_items (id, waybill_id, description, quantity, declared_value, gross_weight_kg) "
             f"VALUES ('{rid()}', '{wid}', {q(d)}, {qty}, {v}, {w});")
    if pay_status == "paid":
        method = R.choice(["cash", "telebirr", "cbe_birr"])
        txn = (method.upper().replace("_", "") + str(R.randint(10**9, 10**10-1))) if method != "cash" else None
        emit(f"INSERT INTO payments (id, waybill_id, method, amount, collected_by, collected_at, transaction_id) "
             f"VALUES ('{rid()}', '{wid}', {q(method)}, {total}, '{AU_AGENT1}', {ts(created + timedelta(hours=1))}, {q(txn)});")

# one customer-initiated 'requested' waybill (no trip yet, priced later at counter)
wid = rid(); wnum = f"{TICKET_PREFIX}-CARGO-2026-{R.randint(0,9_999_999):07d}"
created = NOW - timedelta(days=1, hours=4)
emit(f"INSERT INTO cargo_waybills (id, tenant_id, trip_id, booking_id, waybill_number, "
     f"consignor_name, consignor_phone, consignor_id_number, consignee_name, consignee_phone, consignee_id_number, "
     f"description, declared_value, gross_weight_kg, payment_status, status, consignee_id_verified, issued_by, customer_user_id, created_at) VALUES "
     f"('{wid}', '{OPERATOR_ID}', NULL, NULL, {q(wnum)}, "
     f"{q('customer demo')}, {q(phone())}, NULL, {q(person())}, {q(phone())}, {q('ETH' + str(R.randint(100000,999999)))}, "
     f"{q('Personal effects - 2 boxes clothing and shoes')}, 3000.00, 19.00, 'unpaid', 'requested', false, NULL, '{DEMO_CUSTOMER_APP_USER}', {ts(created)});")
emit(f"INSERT INTO cargo_waybill_items (id, waybill_id, description, quantity, declared_value, gross_weight_kg) VALUES "
     f"('{rid()}', '{wid}', {q('Clothing - box 1')}, 1, 1500.00, 10.00),"
     f"('{rid()}', '{wid}', {q('Shoes - box 2')}, 1, 1500.00, 9.00);")
emit()

emit("COMMIT;")
emit()
emit("-- summary counts")
emit(f"SELECT 'buses' t, count(*) FROM buses WHERE tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'routes', count(*) FROM routes WHERE tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'trips', count(*) FROM trips WHERE tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'trips_scheduled', count(*) FROM trips WHERE tenant_id='{OPERATOR_ID}' AND status='scheduled' "
     f"UNION ALL SELECT 'seats', count(*) FROM seats s JOIN trips tr ON tr.id=s.trip_id WHERE tr.tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'bookings', count(*) FROM bookings WHERE tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'bookings_confirmed', count(*) FROM bookings WHERE tenant_id='{OPERATOR_ID}' AND status='confirmed' "
     f"UNION ALL SELECT 'booking_seats', count(*) FROM booking_seats bs JOIN bookings b ON b.id=bs.booking_id WHERE b.tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'payments', count(*) FROM payments p LEFT JOIN bookings b ON b.id=p.booking_id LEFT JOIN cargo_waybills w ON w.id=p.waybill_id WHERE b.tenant_id='{OPERATOR_ID}' OR w.tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'cargo_waybills', count(*) FROM cargo_waybills WHERE tenant_id='{OPERATOR_ID}' "
     f"UNION ALL SELECT 'confirmed_revenue', COALESCE(SUM(total_amount),0) FROM bookings WHERE tenant_id='{OPERATOR_ID}' AND status='confirmed';")

print("\n".join(out))

import sys
sys.stderr.write(f"generated: {confirmed_count} confirmed, {cancelled_count} cancelled bookings; "
                 f"channels={channel_tally}; {len(trips)} trips ({len(future_trips)} future)\n")
