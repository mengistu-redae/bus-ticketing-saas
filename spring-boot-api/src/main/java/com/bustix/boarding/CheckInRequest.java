package com.bustix.boarding;

import jakarta.validation.constraints.NotBlank;

/** The ID document number presented at the gate, checked against the passenger's booking_seats.passenger_id_number. */
public record CheckInRequest(
    @NotBlank String presentedIdNumber
) {
}
