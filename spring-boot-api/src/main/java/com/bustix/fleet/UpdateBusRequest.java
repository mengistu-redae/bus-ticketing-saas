package com.bustix.fleet;

import jakarta.validation.constraints.Min;

/**
 * Partial update - every field optional, only non-null/non-blank ones are
 * applied. `active` is here too (not just on the DELETE endpoint) so a
 * deactivated bus can be reactivated via PATCH {"active": true}.
 */
public record UpdateBusRequest(
    String plateNo,
    @Min(1) Integer capacity,
    String seatLayout,
    Boolean active
) {
}
