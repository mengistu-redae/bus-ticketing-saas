-- WS-5 of the Partner API: outbound webhooks. A partner registers callback
-- URLs; domain events (booking confirmed/cancelled/rescheduled, trip
-- rescheduled/cancelled, waybill status changes) are fanned out to every
-- matching endpoint for that operator and delivered by a scheduled poller,
-- the same durable-outbox pattern `notifications` / NotificationWorker
-- already use.

CREATE TABLE webhook_endpoints (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES operators(id),
    -- The partner (token azp) that registered and owns this endpoint - it
    -- alone can list/delete it, though delivery fan-out is by tenant_id.
    api_client_id   VARCHAR(255) NOT NULL,
    url             TEXT NOT NULL,
    -- Shown to the partner once at creation; used to HMAC-SHA256 sign each
    -- delivery (X-Bustix-Signature).
    signing_secret  VARCHAR(64) NOT NULL,
    -- Space-delimited event types, or '*' for all.
    event_types     TEXT NOT NULL DEFAULT '*',
    status          VARCHAR(20) NOT NULL DEFAULT 'active', -- active, disabled
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints(tenant_id);
CREATE INDEX idx_webhook_endpoints_client ON webhook_endpoints(api_client_id);

CREATE TABLE webhook_deliveries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id      UUID NOT NULL REFERENCES webhook_endpoints(id),
    -- Stable id for the domain event, echoed in the payload so a partner can
    -- de-duplicate a re-delivered event.
    event_id         UUID NOT NULL,
    event_type       VARCHAR(50) NOT NULL,
    payload          TEXT NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending, delivered, failed
    attempts         INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);
-- The dispatcher's poll: pending rows whose backoff has elapsed, oldest first.
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries(status, next_attempt_at);
