-- Operator branding (Phase 1 of my-notes/operator_branding_scope.md) - a
-- per-operator logo, colours and customer-facing name. Lives on the
-- existing operator_settings singleton (one lazy row per operator, no
-- backfill), same as the business-value overrides and contact info.
--
-- All nullable: NULL means "use the Bustix default" (the frontend applies
-- it - the platform doesn't store a fallback). logo_url holds a URL/path,
-- not the image - there is no asset storage in this app.

ALTER TABLE operator_settings
    ADD COLUMN display_name VARCHAR(255),   -- customer-facing; NULL -> operators.name
    ADD COLUMN tagline      VARCHAR(255),
    ADD COLUMN brand_color  VARCHAR(7),     -- '#RRGGBB'
    ADD COLUMN accent_color VARCHAR(7),
    ADD COLUMN logo_url     VARCHAR(500);
