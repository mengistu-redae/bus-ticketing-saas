package com.bustix.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip as a partner sees it - a deliberately narrow, stable subset of the
 * internal {@code TripSearchResult}. No branding, support-contact or
 * ticket-footer fields: those belong to the operator's own customer-facing
 * ticket, not a distributor's integration. {@code vatRate} is included
 * because a partner pricing a booking needs it to match what
 * {@code POST /v1/bookings} will charge.
 */
public record TripView(
    UUID id,
    UUID operatorId,
    String operatorName,
    String origin,
    String destination,
    String originTerminal,
    String destinationTerminal,
    Instant departureAt,
    Instant arrivalAt,
    BigDecimal price,
    BigDecimal vatRate,
    long availableSeats
) {
}
