package com.bustix.booking;

/** Maps to HTTP 409 in BookingController - see the "already locked" branch in the design diagram. */
public class SeatConflictException extends RuntimeException {
    public SeatConflictException(String message) {
        super(message);
    }
}
