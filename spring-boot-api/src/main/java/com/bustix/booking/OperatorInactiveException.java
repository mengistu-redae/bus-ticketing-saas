package com.bustix.booking;

/** Maps to HTTP 409 in BookingController - the trip's operator has been deactivated (Operator.status != "active"). Booking creation is blocked; marketplace search and staff login are deliberately untouched (see CLAUDE.md's Operator status enforcement note for the scoping reasoning). */
public class OperatorInactiveException extends RuntimeException {
    public OperatorInactiveException(String message) {
        super(message);
    }
}
