-- WS-3 of the Partner API: a general safe-retry mechanism for the /v1
-- surface. Every POST/PATCH under /v1 must carry an `Idempotency-Key`
-- header; IdempotencyFilter records the request here and, on a retry with
-- the same key, replays the stored response instead of running the handler
-- again. This generalises the idempotency the booking-creation path already
-- had (the (tenant_id, idempotency_key) unique on `bookings`) to cancels,
-- reschedules, waybill creation and the whole waybill lifecycle.
--
-- Keyed by (api_client_id, idempotency_key) so two different partners can
-- independently use the same key string. `request_hash` guards against a
-- key being reused with a different body (-> 422). `response_status` is
-- NULL between the first request arriving and its handler completing - a
-- concurrent duplicate in that window gets a 409.

CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The token's `azp` (OAuth client_id) - see api_clients.
    api_client_id    VARCHAR(255) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    method           VARCHAR(10)  NOT NULL,
    path             VARCHAR(255) NOT NULL,
    -- SHA-256 (hex) of the raw request body.
    request_hash     VARCHAR(64)  NOT NULL,
    -- NULL until the first request's handler completes.
    response_status  INT,
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    UNIQUE (api_client_id, idempotency_key)
);

-- Supports the periodic sweep of old keys (a partner need only retry within
-- a short window; keys are not kept forever).
CREATE INDEX idx_idempotency_keys_created ON idempotency_keys(created_at);
