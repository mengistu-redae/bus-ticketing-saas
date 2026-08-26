package com.bustix.cargo;

/** Mirrors com.bustix.refund.BookingAlreadyCancelledException. Mapped to 409. */
public class WaybillAlreadyCancelledException extends RuntimeException {
    public WaybillAlreadyCancelledException(String message) {
        super(message);
    }
}
