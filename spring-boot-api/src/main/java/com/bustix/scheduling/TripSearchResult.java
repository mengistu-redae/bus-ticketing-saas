package com.bustix.scheduling;

import com.bustix.operator.OperatorBrandingView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a cross-tenant marketplace search result - see the "marketplace
 * exception" note in CLAUDE.md. operatorName is denormalized in here (rather
 * than making the caller look it up separately) since the whole point of
 * this endpoint is a customer comparing trips across different operators.
 */
public record TripSearchResult(
    UUID tripId,
    UUID operatorId,
    String operatorName,
    /** Operator's tax id, shown on a passenger ticket - null if the operator has none on file. */
    String operatorTin,
    String origin,
    String destination,
    /** Optional terminal detail for origin/destination - see Route.originTerminal/destinationTerminal. */
    String originTerminal,
    String destinationTerminal,
    Instant departureAt,
    Instant arrivalAt,
    /** departureAt minus bustix.ticketing.reporting-buffer-minutes - when a passenger should be at the terminal. */
    Instant reportingAt,
    BigDecimal price,
    long availableSeats,
    /** The bus's plate number, shown on a passenger ticket. */
    String busPlateNo,
    /** The operator's effective VAT rate (e.g. 0.15) - lets the seat-selection page show the tax-inclusive total the customer will actually be charged. */
    BigDecimal vatRate,
    /** Operator contact / ticket-footer info from operator_settings - null when the operator hasn't provided it. */
    String operatorSupportPhone,
    String operatorSupportEmail,
    String operatorWebsiteUrl,
    String operatorTicketFooterNote,
    /** Operator branding (V13) - displayName falls back to operatorName; colours/logo null when unset. */
    OperatorBrandingView branding
) {
}
