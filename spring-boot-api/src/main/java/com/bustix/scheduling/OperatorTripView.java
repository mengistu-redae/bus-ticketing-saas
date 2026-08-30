package com.bustix.scheduling;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the operator-facing "Trips" management list
 * ({@code GET /api/fleet/trips/manage}). Purpose-built read shape, like
 * {@code WaybillWithItems} / {@code OperatorSettingsResponse}: the bare
 * {@link Trip} entity carries only {@code routeId}/{@code busId}, so the page
 * had to resolve route/bus names client-side and had no seat data at all -
 * this denormalizes the route origin/destination, the bus plate/capacity, and
 * the live seat occupancy the operator actually needs to manage inventory.
 *
 * Not {@link TripSearchResult}: that's the customer marketplace shape and
 * carries no {@code status}/{@code busId}/capacity.
 */
public record OperatorTripView(
    UUID tripId,
    UUID routeId,
    UUID busId,
    String origin,
    String destination,
    String busPlateNo,
    int busCapacity,
    Instant departureAt,
    Instant arrivalAt,
    BigDecimal price,
    String status,
    long availableSeats,
    /** {@code busCapacity - availableSeats} - seats are only ever open or booked. */
    long bookedSeats
) {
}
