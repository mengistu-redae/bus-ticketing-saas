package com.bustix.booking;

/** Maps to HTTP 403 in BookingController - an agent tried to book a trip belonging to a different operator. */
public class TenantMismatchException extends RuntimeException {
    public TenantMismatchException(String message) {
        super(message);
    }
}
