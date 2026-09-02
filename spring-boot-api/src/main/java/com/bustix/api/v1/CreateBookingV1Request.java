package com.bustix.api.v1;

import com.bustix.booking.CreateBookingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * A partner books on behalf of a walk-in passenger who has no Bustix
 * account, so the booking carries the partner's own contact details
 * ({@code channel = "partner"}, same mechanics as a guest booking) rather
 * than a customer id.
 *
 * {@code passengers} reuses the internal {@link CreateBookingRequest.PassengerSeat}
 * shape - a per-seat named passenger with optional phone/ID/age and
 * lap-sitting infants - since that is already a purpose-built request record
 * with its own field validation, not an entity.
 */
public record CreateBookingV1Request(
    @NotNull UUID tripId,
    @NotEmpty @Valid List<CreateBookingRequest.PassengerSeat> passengers,
    @NotNull String idempotencyKey,
    /** E.164 Ethiopian, e.g. +251911234567 - the contact for this booking. */
    @NotBlank
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "contactPhone must be E.164 Ethiopian format, e.g. +251911234567")
    String contactPhone,
    /** Optional - only used to send the confirmation email, never stored. */
    String contactEmail
) {
    /** Maps to the internal request shape; the contact fields flow separately. */
    public CreateBookingRequest toInternal() {
        return new CreateBookingRequest(tripId, passengers, idempotencyKey);
    }
}
