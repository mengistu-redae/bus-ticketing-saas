<#
.SYNOPSIS
  Runs spring-boot-api locally (mvn spring-boot:run) against the local dev
  infra: native Postgres (:5432), Redis in Docker (:6379), native Keycloak
  (:8080) - see infra/keycloak/start-native.ps1.

.DESCRIPTION
  No mvnw wrapper is checked into this repo, so this uses `mvn` on PATH
  (tested against Maven 3.9.16 / Java 21). The values below just restate
  application.yml's own localhost defaults explicitly, so this script is
  self-documenting and easy to point at something else (e.g. a differently
  named local database) without editing application.yml.

.PARAMETER DbUrl
  JDBC URL for the app's own database (bus_ticketing - NOT Keycloak's db).
#>
param(
    [string]$DbUrl = "jdbc:postgresql://localhost:5432/bus_ticketing",
    [string]$DbUsername = "bustix",
    [string]$DbPassword = "bustix",
    [string]$RedisHost = "localhost",
    [string]$RedisPort = "6379",
    [string]$KeycloakIssuerUri = "http://localhost:8080/realms/bustix"
)

$ErrorActionPreference = "Stop"

$env:SPRING_DATASOURCE_URL = $DbUrl
$env:SPRING_DATASOURCE_USERNAME = $DbUsername
$env:SPRING_DATASOURCE_PASSWORD = $DbPassword
$env:SPRING_DATA_REDIS_HOST = $RedisHost
$env:SPRING_DATA_REDIS_PORT = $RedisPort
$env:KEYCLOAK_ISSUER_URI = $KeycloakIssuerUri

Push-Location $PSScriptRoot
try {
    Write-Host "Starting spring-boot-api against $DbUrl (Keycloak issuer: $KeycloakIssuerUri) ..."
    mvn spring-boot:run
}
finally {
    Pop-Location
}
