package com.bustix.booking;

/**
 * Maps to HTTP 409 in BookingRescheduleController - v1 only supports
 * rescheduling single-seat bookings (see BookingRescheduleService's
 * javadoc for why: which new seat maps to which existing passenger,
 * possibly on a different trip, is genuinely ambiguous for a multi-seat
 * booking, and the BRD this feature comes from doesn't specify that level
 * of detail). A multi-seat booking should be cancelled and rebooked
 * instead.
 */
public class MultiSeatRescheduleNotSupportedException extends RuntimeException {
    public MultiSeatRescheduleNotSupportedException(String message) {
        super(message);
    }
}
