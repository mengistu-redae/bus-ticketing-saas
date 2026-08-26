package com.bustix.scheduling;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Partial update - every field optional, only non-null ones are applied.
 * routeId/busId are deliberately not editable here: seats are generated
 * from the bus's capacity/layout at trip creation (see
 * SeatLayoutGenerator), so changing the bus afterwards would leave the
 * existing seats mismatched with the new bus - that needs seat
 * regeneration, a bigger operation than a plain field edit.
 */
public record UpdateTripRequest(
    Instant departureAt,
    Instant arrivalAt,
    BigDecimal price
) {
}
