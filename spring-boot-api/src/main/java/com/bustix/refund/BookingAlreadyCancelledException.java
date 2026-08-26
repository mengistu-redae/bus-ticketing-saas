package com.bustix.refund;

/** Maps to HTTP 409 in CancellationController - the booking was already cancelled. */
public class BookingAlreadyCancelledException extends RuntimeException {
    public BookingAlreadyCancelledException(String message) {
        super(message);
    }
}
