package com.bustix.cargo;

/**
 * Thrown when a waybill is created/re-priced for a route with no
 * operator-wide or route-specific cargo_rates row. Deliberately NOT a
 * silent zero-cost fallback the way RefundCalculator's "no policy = 0%
 * refund" is - a free shipment isn't a safe default, a missing refund
 * policy is. Mapped to 400 in CargoWaybillController.
 */
public class NoCargoRateConfiguredException extends RuntimeException {
    public NoCargoRateConfiguredException(String message) {
        super(message);
    }
}
