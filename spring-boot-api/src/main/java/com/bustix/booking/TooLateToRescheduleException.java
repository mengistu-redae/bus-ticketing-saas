package com.bustix.booking;

/**
 * Maps to HTTP 409 in BookingRescheduleController - the "Time Gate" rule
 * from my-notes/ethiopian_bus_system_specs.md section 5.3: rescheduling
 * needs at least bustix.ticketing.reschedule.min-notice-hours before the
 * current trip's departure. Per the BRD ("block the request and route to
 * the refund engine"), the caller is expected to fall back to the existing
 * cancellation flow instead, not retry this one.
 */
public class TooLateToRescheduleException extends RuntimeException {
    public TooLateToRescheduleException(String message) {
        super(message);
    }
}
