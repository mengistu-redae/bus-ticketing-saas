<#
.SYNOPSIS
  Starts this machine's native Keycloak install (instead of the docker-compose
  keycloak service) pointed at the local Postgres instance, for local dev of
  bus-ticketing-saas.

.DESCRIPTION
  This dev machine already runs Postgres natively on :5432 (shared with other,
  unrelated projects) and a native Keycloak install rather than running
  Keycloak in Docker. This script:

    1. Copies infra/keycloak/realm-export.json (the source of truth checked
       into this repo) into the native Keycloak's data/import/ directory, so
       any realm changes made here are picked up on next start.
    2. Starts Keycloak in dev mode against the `bustix_keycloak` Postgres
       database (a dedicated DB - NOT the "keycloak" db some other project
       on this machine already owns) using the `bustix` role.
    3. --import-realm is idempotent: if the realm already exists in that DB,
       Keycloak logs "already exists. Import skipped" and leaves it alone.

  If you'd rather use the docker-compose keycloak service instead, stop this
  one and run: docker compose start keycloak
  (docker-compose.override.yml already points that container at this same
  host Postgres via host.docker.internal.)

.PARAMETER KeycloakHome
  Path to the native Keycloak install. Defaults to C:\keycloak\keycloak-26.7.1.

.PARAMETER DbUrl
  JDBC URL for Keycloak's own database. Defaults to the bustix_keycloak DB on
  the local Postgres instance.
#>
param(
    [string]$KeycloakHome = "C:\keycloak\keycloak-26.7.1",
    [string]$DbUrl = "jdbc:postgresql://localhost:5432/bustix_keycloak",
    [string]$DbUsername = "bustix",
    [string]$DbPassword = "bustix",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$realmExport = Join-Path $repoRoot "infra\keycloak\realm-export.json"
$importDir = Join-Path $KeycloakHome "data\import"
$kcBat = Join-Path $KeycloakHome "bin\kc.bat"

if (-not (Test-Path $kcBat)) {
    throw "Keycloak not found at '$KeycloakHome' (expected bin\kc.bat there). Pass -KeycloakHome to point at your install."
}
if (-not (Test-Path $realmExport)) {
    throw "Realm export not found at '$realmExport'."
}

New-Item -ItemType Directory -Force -Path $importDir | Out-Null
Copy-Item $realmExport (Join-Path $importDir "realm-export.json") -Force

$env:KC_DB = "postgres"
$env:KC_DB_URL = $DbUrl
$env:KC_DB_USERNAME = $DbUsername
$env:KC_DB_PASSWORD = $DbPassword
$env:KC_HTTP_ENABLED = "true"
$env:KC_HOSTNAME_STRICT = "false"
$env:KEYCLOAK_ADMIN = $AdminUser
$env:KEYCLOAK_ADMIN_PASSWORD = $AdminPassword

Write-Host "Starting native Keycloak from $KeycloakHome against $DbUrl ..."
& $kcBat start-dev --import-realm
