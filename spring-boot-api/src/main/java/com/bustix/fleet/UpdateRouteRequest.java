package com.bustix.fleet;

/**
 * Partial update - every field optional, only non-null/non-blank ones are
 * applied. `active` is here too so a deactivated route can be reactivated
 * via PATCH {"active": true}.
 */
public record UpdateRouteRequest(
    String origin,
    String destination,
    Double distanceKm,
    Boolean active,
    String originTerminal,
    String destinationTerminal
) {
}
