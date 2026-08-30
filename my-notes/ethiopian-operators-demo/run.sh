#!/usr/bin/env bash
# Provision the Ethiopian bus-operator demo roster (Keycloak + Postgres) then
# generate and apply the data seed. provision.py is idempotent; the data seed
# is not (it guards itself and aborts if these operators already have fleet
# rows).
set -euo pipefail
cd "$(dirname "$0")"

export PATH="$PATH:/c/Program Files/PostgreSQL/17/bin"
export PSQL="${PSQL:-psql}"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${PGUSER:-bustix}"
export PGDATABASE="${PGDATABASE:-bus_ticketing}"
export PGPASSWORD="${PGPASSWORD:-bustix}"
export KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
export KC_ADMIN="${KC_ADMIN:-admin}"
export KC_ADMIN_PW="${KC_ADMIN_PW:-admin}"

echo "== 1/3  provision Keycloak orgs + users + operators/app_user rows =="
python provision.py

echo
echo "== 2/3  generate data seed =="
python gen_seed.py > seed_data.sql
wc -l seed_data.sql

echo
echo "== 3/3  apply data seed =="
"$PSQL" -v ON_ERROR_STOP=1 -q -f seed_data.sql
