package com.bustix.scheduling;

import jakarta.validation.constraints.AssertTrue;
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

    /**
     * A trip can't arrive before it departs. Only enforced when both are
     * present; the {@code @NotNull} on departureAt is what flags a missing
     * one. A past-dated departure is deliberately allowed (an operator may
     * backfill history) - the frontend warns but doesn't block.
     */
    @AssertTrue(message = "arrivalAt must be after departureAt")
    public boolean isArrivalAfterDeparture() {
        return arrivalAt == null || departureAt == null || arrivalAt.isAfter(departureAt);
    }
}
