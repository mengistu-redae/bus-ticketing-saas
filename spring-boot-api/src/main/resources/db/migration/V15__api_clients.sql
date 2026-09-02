-- WS-1 of the Partner API (see the "Partner API Build Plan"): a third party
-- integrates by authenticating with the OAuth2 client-credentials grant as a
-- confidential Keycloak client - no human login, no BFF session. Each such
-- client acts on behalf of exactly one operator; it is, in effect, a headless
-- agent. This table is Bustix's local record of that client. Keycloak mints
-- and validates the token; Bustix owns the operator binding, the granted
-- scopes, the rate tier and revocation.
--
-- TenantContextFilter resolves a request's tenant from the token's `azp`
-- claim (the authorized party = the OAuth client_id) against this table when
-- the token carries no `organization` claim - Keycloak service-account
-- tokens don't. A revoked row is a hard API lockout there, the same shape as
-- a deactivated operator.

CREATE TABLE api_clients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The OAuth client_id, which is exactly what a token's `azp` claim carries.
    keycloak_client_id  VARCHAR(255) NOT NULL UNIQUE,
    tenant_id           UUID NOT NULL REFERENCES operators(id),
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'active', -- active, revoked
    -- Space-delimited OAuth scopes granted to this client, mirrored from the
    -- Keycloak client for display and defense-in-depth. Enforced at the /v1
    -- surface (WS-2), not here.
    scopes              TEXT         NOT NULL DEFAULT '',
    -- Consumed by per-partner rate limiting (WS-4); unused until then.
    rate_tier           VARCHAR(20)  NOT NULL DEFAULT 'default',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX idx_api_clients_tenant ON api_clients(tenant_id);
