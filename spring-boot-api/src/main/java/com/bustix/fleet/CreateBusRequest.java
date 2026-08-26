package com.bustix.fleet;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateBusRequest(
    @NotBlank String plateNo,
    @Min(1) int capacity,
    /** Optional - Bus.seatLayout defaults to "2x2" if omitted. */
    String seatLayout
) {
}
