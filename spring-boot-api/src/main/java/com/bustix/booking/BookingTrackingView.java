package com.bustix.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public track-by-ref-and-phone response - see
 * BookingController.trackGuestBooking. Modeled directly on
 * com.bustix.cargo.WaybillTrackingView: deliberately narrow (no ID numbers,
 * no other passenger's phone) since this path has no login and no tenant
 * check beyond the phone match in BookingService.trackByRefAndPhone.
 */
public record BookingTrackingView(
    String bookingRef,
    String ticketNumber,
    String status,
    String channel,
    UUID tripId,
    String origin,
    String destination,
    Instant departureAt,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    List<SeatView> seats,
    /** Operator support contact from operator_settings - null when not provided. Not sensitive (it's the operator's public contact). */
    String operatorSupportPhone,
    String operatorSupportEmail
) {
    public record SeatView(String seatNo, String passengerName) {
    }
}
