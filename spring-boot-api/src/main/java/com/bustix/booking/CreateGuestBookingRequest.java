package com.bustix.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * Same shape as CreateBookingRequest, for a booking with no logged-in
 * customer behind it at all - see BookingController.createGuestBooking.
 * Reuses CreateBookingRequest.PassengerSeat/Infant as-is rather than
 * duplicating them; the only thing a guest booking needs that an
 * authenticated one gets for free from the JWT is a way to identify the
 * booker: contactPhone doubles as both the notification target and the
 * second factor for BookingController.trackGuestBooking later.
 * contactEmail is optional (only used for the outbox confirmation
 * notification, never persisted - see BookingWriter) since a guest at a
 * counter or on a bus-only trip may not have one.
 */
public record CreateGuestBookingRequest(
    @NotNull UUID tripId,
    @NotEmpty @Valid List<CreateBookingRequest.PassengerSeat> passengers,
    @NotNull String idempotencyKey,
    /** E.164 Ethiopian format, e.g. +251911234567 - required, unlike a passenger's own optional phone. */
    @NotBlank
    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
    String contactPhone,
    @Email String contactEmail
) {
}
