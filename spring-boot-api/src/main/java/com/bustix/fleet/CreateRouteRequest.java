package com.bustix.fleet;

import jakarta.validation.constraints.NotBlank;

public record CreateRouteRequest(
    @NotBlank String origin,
    @NotBlank String destination,
    /** Optional - distance_km is nullable in the schema. */
    Double distanceKm,
    /** Optional terminal detail shown on a passenger ticket - see V3__ticketing_details.sql. */
    String originTerminal,
    String destinationTerminal
) {
}
