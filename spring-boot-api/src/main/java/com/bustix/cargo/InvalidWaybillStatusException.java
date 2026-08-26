package com.bustix.cargo;

/**
 * Thrown for any state-machine transition attempted from the wrong current
 * status - dispatching a cancelled waybill, arriving one that was never
 * dispatched, collecting one that hasn't arrived, or PATCHing a
 * physical-shipment field once status != "issued" (the "Immutability
 * Principle" carried over from passenger tickets - see
 * my-notes/cargo_logistics_scope_v1.md decision 11). Mapped to 409, same as
 * BoardingClosedException/TooLateToRescheduleException for the equivalent
 * booking-side rules. A waybill that's already cancelled specifically uses
 * WaybillAlreadyCancelledException instead, so that one case stays
 * distinguishable from every other invalid transition.
 */
public class InvalidWaybillStatusException extends RuntimeException {
    public InvalidWaybillStatusException(String message) {
        super(message);
    }
}
