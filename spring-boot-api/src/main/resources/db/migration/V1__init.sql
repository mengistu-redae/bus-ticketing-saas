CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenants: one row per bus operator company. tenant_id everywhere below
-- refers to operators.id, not the Keycloak organization id directly - we
-- keep our own UUID as the tenant key so the app is not hard-wired to
-- Keycloak's id format.
CREATE TABLE operators (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_org_id  VARCHAR(64) NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Staff (platform_admin, operator_admin, agent) have tenant_id set.
-- Customers have tenant_id = NULL because they browse and book across
-- every operator on the platform - see the "marketplace" note in the README.
CREATE TABLE app_user (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id  VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         UUID REFERENCES operators(id),
    role              VARCHAR(30) NOT NULL,
    display_name      VARCHAR(255),
    email             VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);

CREATE TABLE buses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES operators(id),
    plate_no     VARCHAR(20) NOT NULL,
    capacity     INT NOT NULL,
    seat_layout  VARCHAR(20) NOT NULL DEFAULT '2x2',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_buses_tenant ON buses(tenant_id);

CREATE TABLE routes (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES operators(id),
    origin       VARCHAR(120) NOT NULL,
    destination  VARCHAR(120) NOT NULL,
    distance_km  NUMERIC(6,1),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_routes_tenant ON routes(tenant_id);
-- Supports the cross-tenant customer search path ("all operators, city A to B").
CREATE INDEX idx_routes_search ON routes(origin, destination);

CREATE TABLE trips (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES operators(id),
    route_id      UUID NOT NULL REFERENCES routes(id),
    bus_id        UUID NOT NULL REFERENCES buses(id),
    departure_at  TIMESTAMPTZ NOT NULL,
    arrival_at    TIMESTAMPTZ,
    price         NUMERIC(10,2) NOT NULL, -- flat price per seat for v1
    status        VARCHAR(20) NOT NULL DEFAULT 'scheduled',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trips_tenant ON trips(tenant_id);
CREATE INDEX idx_trips_departure ON trips(departure_at);

CREATE TABLE seats (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     UUID NOT NULL REFERENCES trips(id),
    seat_no     VARCHAR(10) NOT NULL,
    -- Unused for pricing in v1 (flat pricing), kept so per-class pricing
    -- can be added later without a schema migration.
    seat_class  VARCHAR(20) NOT NULL DEFAULT 'standard',
    status      VARCHAR(20) NOT NULL DEFAULT 'open', -- open, booked
    UNIQUE (trip_id, seat_no)
);
CREATE INDEX idx_seats_trip ON seats(trip_id);

CREATE TABLE bookings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES operators(id),
    trip_id            UUID NOT NULL REFERENCES trips(id),
    customer_user_id   UUID REFERENCES app_user(id),
    agent_user_id      UUID REFERENCES app_user(id), -- set only for counter bookings
    channel            VARCHAR(20) NOT NULL,          -- self_service, counter
    status             VARCHAR(20) NOT NULL DEFAULT 'confirmed', -- confirmed, cancelled
    idempotency_key    VARCHAR(64) NOT NULL,
    total_amount       NUMERIC(10,2) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_bookings_tenant ON bookings(tenant_id);

CREATE TABLE booking_seats (
    booking_id  UUID NOT NULL REFERENCES bookings(id),
    seat_id     UUID NOT NULL REFERENCES seats(id),
    price       NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (booking_id, seat_id)
);

CREATE TABLE payments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id    UUID NOT NULL REFERENCES bookings(id),
    method        VARCHAR(20) NOT NULL DEFAULT 'cash', -- cash today, gateway later
    amount        NUMERIC(10,2) NOT NULL,
    collected_by  UUID REFERENCES app_user(id),
    collected_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Operator-configurable refund policy. `rules` is an ordered list of tiers,
-- e.g. [{"cutoff_hours":24,"refund_percent":100},{"cutoff_hours":2,"refund_percent":50},{"cutoff_hours":0,"refund_percent":0}]
-- route_id NULL = operator-wide default; a specific route_id overrides it.
CREATE TABLE refund_policies (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES operators(id),
    route_id    UUID REFERENCES routes(id),
    rules       JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refund_policies_tenant ON refund_policies(tenant_id);

CREATE TABLE cancellations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id     UUID NOT NULL REFERENCES bookings(id),
    cancelled_by   UUID REFERENCES app_user(id), -- agent or operator_admin, both allowed
    reason         VARCHAR(255),
    refund_amount  NUMERIC(10,2) NOT NULL,
    refunded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Outbox pattern: written in the same transaction as the booking/cancellation,
-- dispatched asynchronously by NotificationWorker so a flaky email provider
-- never fails a booking.
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL REFERENCES bookings(id),
    channel     VARCHAR(20) NOT NULL DEFAULT 'email', -- email for v1, sms/push later
    recipient   VARCHAR(255) NOT NULL,
    template    VARCHAR(50) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending, sent, failed
    attempts    INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX idx_notifications_status ON notifications(status);
