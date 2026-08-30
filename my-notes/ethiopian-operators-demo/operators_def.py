#!/usr/bin/env python3
"""Static definition of the Ethiopian bus-operator demo roster.

Twelve real, well-known Ethiopian intercity bus operators. Names, cities and
route corridors are real; TINs, plate numbers, staff people, phone numbers,
prices and all booking/cargo data are synthetic demo data.

Shared by provision.py (Keycloak orgs + users + operators/app_user rows) and
gen_seed.py (fleet / trips / bookings / cargo).
"""
import random

# ---------------------------------------------------------------- city table
# distance in km is "from Addis Ababa" (the national hub); routes that don't
# touch Addis carry an explicit km in the operator's `extra` list.
CITY = {
    "Addis Ababa":  (0,    "Meskel Square Bus Terminal"),
    "Adama":        (100,  "Adama Bus Terminal"),
    "Debre Birhan": (130,  "Debre Birhan Bus Terminal"),
    "Shashamane":   (250,  "Shashamane Bus Terminal"),
    "Hawassa":      (275,  "Hawassa Bus Terminal"),
    "Debre Markos": (300,  "Debre Markos Bus Terminal"),
    "Nekemte":      (331,  "Nekemte Bus Terminal"),
    "Jimma":        (352,  "Jimma Bus Terminal"),
    "Dilla":        (359,  "Dilla Bus Terminal"),
    "Kombolcha":    (376,  "Kombolcha Bus Terminal"),
    "Wolaita Sodo": (385,  "Wolaita Sodo Bus Terminal"),
    "Dessie":       (401,  "Dessie Bus Terminal"),
    "Robe":         (430,  "Robe (Bale) Bus Terminal"),
    "Dire Dawa":    (445,  "Dire Dawa Bus Terminal"),
    "Arba Minch":   (505,  "Arba Minch Bus Terminal"),
    "Woldiya":      (521,  "Woldiya Bus Terminal"),
    "Harar":        (526,  "Harar Bus Terminal"),
    "Bahir Dar":    (565,  "Bahir Dar Bus Terminal"),
    "Semera":       (588,  "Semera Bus Terminal"),
    "Jijiga":       (628,  "Jijiga Bus Terminal"),
    "Assosa":       (687,  "Assosa Bus Terminal"),
    "Gondar":       (727,  "Gondar Bus Terminal"),
    "Gambela":      (766,  "Gambela Bus Terminal"),
    "Mekelle":      (783,  "Mekelle Bus Terminal"),
    "Adigrat":      (898,  "Adigrat Bus Terminal"),
    "Axum":         (1024, "Axum Bus Terminal"),
}

REGION_CODE = {
    "Addis Ababa": "AA", "Adama": "OR", "Bahir Dar": "AM", "Hawassa": "SD",
}

# ---------------------------------------------------------------- roster
# Each operator:
#   name          legal / marketplace name (drives TicketNumberGenerator prefix)
#   short         display_name for branding
#   slug          keycloak username stem  ->  <slug>-admin / <slug>-agent1 / -agent2
#   alias         keycloak organization alias  ==  operators.keycloak_org_id
#   tin           synthetic taxpayer id
#   domain        staff email domain + website
#   brand/accent  hex colours for operator_settings branding (V13)
#   tagline       branding tagline
#   hub           origin city for this operator's routes
#   dests         destination cities (route created each direction hub<->dest)
#   extra         [(cityA, cityB, km)] extra non-hub route pairs (each direction)
#   staff         [(display name, role)] - first is operator_admin, rest agents
OPERATORS = [
    dict(name="Sky Bus Transport System", short="Sky Bus", slug="skybus", alias="sky-bus",
         tin="0011002001", domain="skybus.et",
         brand="#0284C7", accent="#F97316",
         tagline="Ethiopia's skies, on the road.",
         hub="Addis Ababa",
         dests=["Bahir Dar", "Gondar", "Mekelle", "Dessie", "Dire Dawa", "Hawassa"],
         extra=[],
         staff=[("Yonas Tesfaye", "operator_admin"), ("Kalkidan Bekele", "agent"), ("Natnael Girma", "agent")]),

    dict(name="Golden Bus Transport", short="Golden Bus", slug="goldenbus", alias="golden-bus",
         tin="0011002002", domain="goldenbus.et",
         brand="#B45309", accent="#1E3A8A",
         tagline="The golden standard in intercity travel.",
         hub="Addis Ababa",
         dests=["Bahir Dar", "Gondar", "Axum", "Mekelle", "Adigrat", "Woldiya"],
         extra=[("Bahir Dar", "Gondar", 180)],
         staff=[("Selamawit Haile", "operator_admin"), ("Robel Assefa", "agent"), ("Mekdes Tadesse", "agent")]),

    dict(name="Abay Bus", short="Abay Bus", slug="abaybus", alias="abay-bus",
         tin="0011002003", domain="abaybus.et",
         brand="#1D4ED8", accent="#0D9488",
         tagline="Along the Blue Nile and beyond.",
         hub="Bahir Dar",
         dests=["Addis Ababa", "Gondar", "Debre Markos", "Dessie"],
         extra=[("Bahir Dar", "Mekelle", 470), ("Bahir Dar", "Gondar", 180)],
         staff=[("Alemu Worku", "operator_admin"), ("Tigist Mengistu", "agent"), ("Getachew Alene", "agent")]),

    dict(name="Habesha Bus", short="Habesha Bus", slug="habeshabus", alias="habesha-bus",
         tin="0011002004", domain="habeshabus.et",
         brand="#B91C1C", accent="#15803D",
         tagline="Proudly Ethiopian, everywhere we go.",
         hub="Addis Ababa",
         dests=["Hawassa", "Arba Minch", "Jimma", "Dilla", "Wolaita Sodo", "Shashamane"],
         extra=[],
         staff=[("Hirut Desta", "operator_admin"), ("Samuel Kebede", "agent"), ("Bethlehem Fikadu", "agent")]),

    dict(name="Ethio Bus", short="Ethio Bus", slug="ethiobus", alias="ethio-bus",
         tin="0011002005", domain="ethiobus.et",
         brand="#047857", accent="#F59E0B",
         tagline="One country, one network.",
         hub="Addis Ababa",
         dests=["Dire Dawa", "Harar", "Jijiga", "Dessie", "Kombolcha", "Semera"],
         extra=[("Dire Dawa", "Harar", 55)],
         staff=[("Dawit Solomon", "operator_admin"), ("Marta Girma", "agent"), ("Abel Tariku", "agent")]),

    dict(name="Zemen Bus", short="Zemen Bus", slug="zemenbus", alias="zemen-bus",
         tin="0011002006", domain="zemenbus.et",
         brand="#6D28D9", accent="#DB2777",
         tagline="Modern travel for a new era.",
         hub="Addis Ababa",
         dests=["Bahir Dar", "Gondar", "Mekelle", "Hawassa", "Jimma", "Dire Dawa"],
         extra=[],
         staff=[("Eyob Assefa", "operator_admin"), ("Lidya Bekele", "agent"), ("Henok Girma", "agent")]),

    dict(name="Liyu Bus", short="Liyu Bus", slug="liyubus", alias="liyu-bus",
         tin="0011002007", domain="liyubus.et",
         brand="#BE185D", accent="#4338CA",
         tagline="Special service, every trip.",
         hub="Addis Ababa",
         dests=["Bahir Dar", "Gondar", "Woldiya", "Mekelle", "Dessie"],
         extra=[],
         staff=[("Rahel Tesfaye", "operator_admin"), ("Biruk Alemu", "agent"), ("Frehiwot Kebede", "agent")]),

    dict(name="Walia Bus Transport", short="Walia Bus", slug="waliabus", alias="walia-bus",
         tin="0011002008", domain="waliabus.et",
         brand="#92400E", accent="#CA8A04",
         tagline="Sure-footed on every route.",
         hub="Addis Ababa",
         dests=["Gondar", "Bahir Dar", "Debre Markos", "Axum", "Adigrat"],
         extra=[],
         staff=[("Tadesse Gebre", "operator_admin"), ("Meron Wolde", "agent"), ("Yohannes Negash", "agent")]),

    dict(name="Getbus", short="Getbus", slug="getbus", alias="getbus",
         tin="0011002009", domain="getbus.et",
         brand="#0F766E", accent="#EA580C",
         tagline="The South's connection to Ethiopia.",
         hub="Hawassa",
         dests=["Addis Ababa", "Arba Minch", "Dilla", "Wolaita Sodo", "Shashamane"],
         extra=[("Hawassa", "Jimma", 290)],
         staff=[("Aster Lemma", "operator_admin"), ("Nahom Desta", "agent"), ("Kidist Fantahun", "agent")]),

    dict(name="Oda Bus Transport", short="Oda Bus", slug="odabus", alias="oda-bus",
         tin="0011002010", domain="odabus.et",
         brand="#166534", accent="#CA8A04",
         tagline="Rooted in Oromia, reaching everywhere.",
         hub="Adama",
         dests=["Addis Ababa", "Hawassa", "Dire Dawa", "Jimma", "Robe", "Nekemte"],
         extra=[],
         staff=[("Gadisa Tola", "operator_admin"), ("Chaltu Bekele", "agent"), ("Lelisa Girma", "agent")]),

    dict(name="Alsam Bus", short="Alsam Bus", slug="alsambus", alias="alsam-bus",
         tin="0011002011", domain="alsambus.et",
         brand="#4338CA", accent="#0891B2",
         tagline="Comfort that goes the distance.",
         hub="Addis Ababa",
         dests=["Mekelle", "Adigrat", "Axum", "Woldiya", "Dessie"],
         extra=[],
         staff=[("Semir Ahmed", "operator_admin"), ("Hanna Yosef", "agent"), ("Kirubel Kebede", "agent")]),

    dict(name="Yebeza Bus", short="Yebeza Bus", slug="yebezabus", alias="yebeza-bus",
         tin="0011002012", domain="yebezabus.et",
         brand="#9333EA", accent="#16A34A",
         tagline="Travel like family.",
         hub="Addis Ababa",
         dests=["Gambela", "Assosa", "Nekemte", "Jimma", "Gondar"],
         extra=[],
         staff=[("Firaol Bekele", "operator_admin"), ("Saron Tadesse", "agent"), ("Amanuel Girma", "agent")]),
]


def round50(x):
    return int(round(x / 50.0)) * 50


def route_price(km):
    return max(200, round50(km * 2.6))


def slug_email(name, domain):
    parts = name.lower().split()
    return f"{parts[0]}.{parts[-1]}@{domain}"


def first_last(name):
    parts = name.split()
    return parts[0], " ".join(parts[1:]) if len(parts) > 1 else parts[0]


def op_rng(op):
    return random.Random(hash(op["slug"]) & 0xFFFFFFFF)


def buses_for(op):
    """4 deterministic buses for an operator."""
    R = random.Random((hash(op["slug"]) ^ 0x5EED) & 0xFFFFFFFF)
    code = REGION_CODE.get(op["hub"], "AA")
    layouts = [(49, "2x2"), (51, "2x2"), (45, "2x2"), (44, "2x2"), (60, "2x3")]
    out = []
    picks = [layouts[0], layouts[1], R.choice(layouts[2:]), R.choice(layouts)]
    for cap, layout in picks:
        out.append((f"3-{R.randint(10000, 99999)} {code}", cap, layout))
    return out


def routes_for(op):
    """List of (origin, destination, distance_km, origin_terminal, dest_terminal, price).

    hub<->dest both directions, plus each `extra` pair both directions.
    """
    hub = op["hub"]
    hub_term = CITY[hub][1]
    out = []
    for dest in op["dests"]:
        km = CITY[dest][0] if hub == "Addis Ababa" else abs(CITY[hub][0] - CITY[dest][0]) or CITY[dest][0]
        if dest == "Addis Ababa":
            km = CITY[hub][0]
        dterm = CITY[dest][1]
        price = route_price(km)
        out.append((hub, dest, km, hub_term, dterm, price))
        out.append((dest, hub, km, dterm, hub_term, price))
    for a, b, km in op["extra"]:
        out.append((a, b, km, CITY[a][1], CITY[b][1], route_price(km)))
        out.append((b, a, km, CITY[b][1], CITY[a][1], route_price(km)))
    return out
