package com.bustix.booking;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * v1 only supports single-seat bookings (see BookingRescheduleService's
 * javadoc) - newTripId/newSeatId move the booking's one seat, not a list.
 * Per the BRD's "Immutability Principle" (my-notes/
 * ethiopian_bus_system_specs.md section 5.3), passenger name/ID fields
 * are never editable here - they carry over unchanged from the existing
 * booking_seats row.
 */
public record RescheduleBookingRequest(
    @NotNull UUID newTripId,
    @NotNull UUID newSeatId
) {
}
