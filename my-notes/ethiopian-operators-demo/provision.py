#!/usr/bin/env python3
"""Provision the Ethiopian bus-operator demo roster into Keycloak + Postgres.

Idempotent. Safe to re-run.

For every operator in operators_def.OPERATORS:
  Keycloak (realm 'bustix'):
    - get-or-create the Organization (alias == operators.keycloak_org_id)
    - get-or-create 3 staff users (<slug>-admin, <slug>-agent1, <slug>-agent2),
      password 'changeme123' (non-temporary), realm role assigned, added as
      an organization member
  Postgres (bus_ticketing):
    - upsert the operators row  (ON CONFLICT (keycloak_org_id) keeps an
      existing id - e.g. the pre-existing 'zemen-bus' stub)
    - upsert an app_user row per staff member (mirrors what first-login
      provisioning would create, so the data-seed FKs resolve)

Writes _provision.json (operator ids + keycloak/app_user ids) for gen_seed.py.

Env (all optional):
  KEYCLOAK_URL   default http://localhost:8080
  KC_ADMIN       default admin
  KC_ADMIN_PW    default admin
  PSQL           default 'psql'   (path to psql client)
  PGHOST/PGUSER/PGDATABASE/PGPASSWORD  default localhost/bustix/bus_ticketing/bustix
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from operators_def import OPERATORS, first_last, slug_email

KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8080").rstrip("/")
KC_ADMIN = os.environ.get("KC_ADMIN", "admin")
KC_ADMIN_PW = os.environ.get("KC_ADMIN_PW", "admin")
REALM = "bustix"
DEFAULT_PW = "changeme123"

PSQL = os.environ.get("PSQL", "psql")
PGENV = {
    **os.environ,
    "PGHOST": os.environ.get("PGHOST", "localhost"),
    "PGUSER": os.environ.get("PGUSER", "bustix"),
    "PGDATABASE": os.environ.get("PGDATABASE", "bus_ticketing"),
    "PGPASSWORD": os.environ.get("PGPASSWORD", "bustix"),
}

HERE = os.path.dirname(os.path.abspath(__file__))


# ----------------------------------------------------------------- keycloak
def _req(method, path, token=None, body=None, form=None):
    url = path if path.startswith("http") else f"{KC}{path}"
    headers = {}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read().decode() or "null"
            return resp.status, dict(resp.headers), json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, dict(e.headers), raw


_TOKEN = [None]


def admin_token():
    st, _, payload = _req("POST", f"/realms/master/protocol/openid-connect/token", form={
        "client_id": "admin-cli", "username": KC_ADMIN,
        "password": KC_ADMIN_PW, "grant_type": "password",
    })
    if st != 200:
        sys.exit(f"admin login failed ({st}): {payload}")
    _TOKEN[0] = payload["access_token"]
    return _TOKEN[0]


def kc(method, path, body=None, form=None):
    """Keycloak admin call that transparently re-logs-in on a 401 (the
    admin-cli access token lives ~60s, shorter than a full provisioning run)."""
    st, headers, payload = _req(method, path, _TOKEN[0], body=body, form=form)
    if st == 401:
        admin_token()
        st, headers, payload = _req(method, path, _TOKEN[0], body=body, form=form)
    return st, headers, payload


def realm_roles():
    st, _, payload = kc("GET", f"/admin/realms/{REALM}/roles")
    return {r["name"]: r for r in payload}


def get_or_create_org(op):
    st, _, orgs = kc("GET", f"/admin/realms/{REALM}/organizations?max=200")
    for o in orgs:
        if o.get("alias") == op["alias"]:
            return o["id"], False
    body = {
        "name": op["name"], "alias": op["alias"], "enabled": True,
        "domains": [{"name": op["domain"], "verified": True}],
    }
    st, headers, payload = kc("POST", f"/admin/realms/{REALM}/organizations", body=body)
    if st not in (201, 204):
        sys.exit(f"[{op['alias']}] org create failed ({st}): {payload}")
    loc = headers.get("Location") or headers.get("location", "")
    org_id = loc.rstrip("/").split("/")[-1]
    if not org_id:
        st, _, orgs = kc("GET", f"/admin/realms/{REALM}/organizations?max=200")
        org_id = next(o["id"] for o in orgs if o["alias"] == op["alias"])
    return org_id, True


def get_or_create_user(username, first, last, email):
    qs = urllib.parse.urlencode({"username": username, "exact": "true"})
    st, _, found = kc("GET", f"/admin/realms/{REALM}/users?{qs}")
    if isinstance(found, list) and found:
        uid = found[0]["id"]
        created = False
    else:
        body = {
            "username": username, "enabled": True, "emailVerified": True,
            "firstName": first, "lastName": last, "email": email,
            "requiredActions": [],
        }
        st, headers, payload = kc("POST", f"/admin/realms/{REALM}/users", body=body)
        if st not in (201, 204):
            sys.exit(f"user {username} create failed ({st}): {payload}")
        loc = headers.get("Location") or headers.get("location", "")
        uid = loc.rstrip("/").split("/")[-1]
        created = True
    st, _, payload = kc("PUT", f"/admin/realms/{REALM}/users/{uid}/reset-password",
                        body={"type": "password", "value": DEFAULT_PW, "temporary": False})
    if st not in (204, 200):
        print(f"  ! {username}: set-password returned {st}: {payload}")
    return uid, created


def assign_role(uid, role_rep):
    st, _, payload = kc("POST", f"/admin/realms/{REALM}/users/{uid}/role-mappings/realm",
                        body=[{"id": role_rep["id"], "name": role_rep["name"]}])
    if st not in (204, 200):
        print(f"  ! role assign returned {st}: {payload}")


def add_member(org_id, uid):
    st, _, payload = kc("POST", f"/admin/realms/{REALM}/organizations/{org_id}/members", body=uid)
    if st not in (201, 204, 409):
        print(f"  ! add member returned {st}: {payload}")


# ----------------------------------------------------------------- postgres
def psql_exec(sql):
    p = subprocess.run([PSQL, "-v", "ON_ERROR_STOP=1", "-q", "-c", sql],
                       env=PGENV, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"psql failed:\n{p.stderr}\n--- sql ---\n{sql}")
    return p.stdout


def psql_query(sql):
    p = subprocess.run([PSQL, "-tA", "-F", "\t", "-c", sql],
                       env=PGENV, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"psql query failed:\n{p.stderr}")
    return [line.split("\t") for line in p.stdout.splitlines() if line.strip()]


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


# ----------------------------------------------------------------- main
def main():
    admin_token()
    roles = realm_roles()
    for r in ("operator_admin", "agent"):
        if r not in roles:
            sys.exit(f"realm role {r!r} missing")

    provision = {}
    for op in OPERATORS:
        print(f"[{op['alias']}] {op['name']}")
        org_id, org_new = get_or_create_org(op)
        print(f"  org {'created' if org_new else 'exists'}: {org_id}")

        staff = []
        for idx, (name, role) in enumerate(op["staff"]):
            suffix = "admin" if role == "operator_admin" else f"agent{idx}"
            username = f"{op['slug']}-{suffix}"
            first, last = first_last(name)
            email = slug_email(name, op["domain"])
            uid, u_new = get_or_create_user(username, first, last, email)
            assign_role(uid, roles[role])
            add_member(org_id, uid)
            print(f"  user {'created' if u_new else 'exists'}: {username} ({role})")
            staff.append(dict(
                username=username, role=role, name=name, email=email,
                keycloak_user_id=uid,
                app_user_id=str(uuid.uuid5(uuid.NAMESPACE_DNS, f"bustix-appuser:{username}")),
            ))

        provision[op["alias"]] = dict(
            name=op["name"], short=op["short"], slug=op["slug"], alias=op["alias"],
            org_id=org_id,
            operator_id=str(uuid.uuid5(uuid.NAMESPACE_DNS, f"bustix-operator:{op['alias']}")),
            staff=staff,
        )

    # ---- operators rows (upsert; existing id wins on conflict)
    op_sql = ["BEGIN;"]
    for op in OPERATORS:
        p = provision[op["alias"]]
        op_sql.append(
            f"INSERT INTO operators (id, keycloak_org_id, name, tin, status) VALUES "
            f"({q(p['operator_id'])}, {q(op['alias'])}, {q(op['name'])}, {q(op['tin'])}, 'active') "
            f"ON CONFLICT (keycloak_org_id) DO UPDATE SET "
            f"name = EXCLUDED.name, tin = EXCLUDED.tin, status = 'active', updated_at = now();")
    op_sql.append("COMMIT;")
    psql_exec("\n".join(op_sql))

    # ---- resolve real operator ids (zemen-bus keeps its pre-existing id)
    id_map = {alias: oid for alias, oid in psql_query(
        "SELECT keycloak_org_id, id FROM operators WHERE keycloak_org_id IN ("
        + ",".join(q(op["alias"]) for op in OPERATORS) + ");")}
    for alias, oid in id_map.items():
        provision[alias]["operator_id"] = oid

    # ---- app_user rows (mirror the Keycloak staff so seed FKs resolve)
    au_sql = ["BEGIN;"]
    for op in OPERATORS:
        p = provision[op["alias"]]
        for s in p["staff"]:
            au_sql.append(
                f"INSERT INTO app_user (id, keycloak_user_id, tenant_id, role, display_name, email) VALUES "
                f"({q(s['app_user_id'])}, {q(s['keycloak_user_id'])}, {q(p['operator_id'])}, "
                f"{q(s['role'])}, {q(s['name'])}, {q(s['email'])}) "
                f"ON CONFLICT (keycloak_user_id) DO UPDATE SET "
                f"tenant_id = EXCLUDED.tenant_id, role = EXCLUDED.role, "
                f"display_name = EXCLUDED.display_name, email = EXCLUDED.email;")
    au_sql.append("COMMIT;")
    psql_exec("\n".join(au_sql))

    out = os.path.join(HERE, "_provision.json")
    with open(out, "w") as f:
        json.dump(provision, f, indent=2)
    print(f"\nwrote {out}")
    print(f"operators: {len(provision)}  users: {sum(len(p['staff']) for p in provision.values())}")


if __name__ == "__main__":
    main()
