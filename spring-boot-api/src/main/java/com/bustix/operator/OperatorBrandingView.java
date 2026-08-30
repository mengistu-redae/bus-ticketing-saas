package com.bustix.operator;

/**
 * An operator's branding, resolved for display. One shared read shape:
 * returned by {@code GET /api/operator/branding} and embedded in
 * {@code TripSearchResult} / {@code BookingTrackingView} /
 * {@code WaybillTrackingView}.
 *
 * {@code displayName} falls back to the operator's legal name when unset;
 * the colours and {@code logoUrl} stay null when unset - the frontend
 * applies the Bustix defaults, the platform stores none.
 */
public record OperatorBrandingView(
    String displayName,
    String tagline,
    String brandColor,
    String accentColor,
    String logoUrl
) {

    public static OperatorBrandingView from(EffectiveOperatorSettings s, String operatorFallbackName) {
        return new OperatorBrandingView(
                s.displayName() != null ? s.displayName() : operatorFallbackName,
                s.tagline(),
                s.brandColor(),
                s.accentColor(),
                s.logoUrl());
    }
}
