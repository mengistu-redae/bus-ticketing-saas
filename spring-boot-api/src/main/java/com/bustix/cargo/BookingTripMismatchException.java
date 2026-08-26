package com.bustix.cargo;

/**
 * Thrown when a waybill's optional bookingId points at a booking for a
 * *different* trip than the waybill itself - accompanied excess baggage
 * has to travel with the passenger it belongs to. Mapped to 400 (a bad
 * request, not a conflict with existing state).
 */
public class BookingTripMismatchException extends RuntimeException {
    public BookingTripMismatchException(String message) {
        super(message);
    }
}
