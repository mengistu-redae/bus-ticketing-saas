package com.bustix.scheduling;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateTripRequest(
    @NotNull UUID routeId,
    @NotNull UUID busId,
    @NotNull Instant departureAt,
    /** Optional - arrival_at is nullable in the schema. */
    Instant arrivalAt,
    @NotNull @DecimalMin("0.0") BigDecimal price
) {
}
