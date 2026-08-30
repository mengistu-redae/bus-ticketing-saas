-- Per-operator settings (see CLAUDE.md's "Per-operator settings" section).
-- Until now every operator ran on the same platform-wide business config
-- baked into application.yml (bustix.ticketing.*). This table lets an
-- operator_admin override those values for their own operator, carry
-- operator contact / ticket-footer info, and govern the reschedule
-- notification behaviour.
--
-- One row per operator, tenant_id as the primary key - this is a singleton
-- settings record, not a collection, so there is no separate surrogate id.
--
-- Every business-value override column is NULLABLE: NULL means "fall back to
-- the application.yml default", resolved by OperatorSettingsService.resolve.
-- No backfill - an operator with no row here resolves entirely to platform
-- defaults, and the row is created lazily on the first
-- PATCH /api/fleet/settings.

CREATE TABLE operator_settings (
    tenant_id                        UUID PRIMARY KEY REFERENCES operators(id),

    -- business-value overrides; NULL = use the application.yml default
    vat_rate                         NUMERIC(6,4),
    reporting_buffer_minutes         INTEGER,
    reschedule_min_notice_hours      INTEGER,
    reschedule_fee_self_service      NUMERIC(12,2),
    reschedule_fee_counter           NUMERIC(12,2),

    -- notification governance: gates both the existing per-booking
    -- "booking_rescheduled" notice and the new trip-time-change cascade
    -- (TripUpdateService). Defaults on.
    reschedule_notifications_enabled BOOLEAN NOT NULL DEFAULT true,

    -- informational, shown on tickets / tracking pages; NULL = not provided
    support_phone                    VARCHAR(20),
    support_email                    VARCHAR(255),
    support_address                  VARCHAR(500),
    website_url                      VARCHAR(255),
    ticket_footer_note               VARCHAR(500),

    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);
