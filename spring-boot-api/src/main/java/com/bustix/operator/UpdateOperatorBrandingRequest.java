package com.bustix.operator;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/operator/branding}. Full replace of the
 * branding fields only (disjoint from {@code PATCH /api/fleet/settings}) -
 * a null field clears that branding value, and the frontend then shows the
 * Bustix default. Bean validation skips null, so the frontend sends null
 * (not {@code ""}) to clear.
 */
public record UpdateOperatorBrandingRequest(

    @Size(max = 255)
    String displayName,

    @Size(max = 255)
    String tagline,

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "brandColor must be a hex colour like #1D4ED8")
    String brandColor,

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "accentColor must be a hex colour like #F59E0B")
    String accentColor,

    @Size(max = 500)
    @Pattern(regexp = "^https?://.+", message = "logoUrl must be an http(s) URL")
    String logoUrl
) {
}
