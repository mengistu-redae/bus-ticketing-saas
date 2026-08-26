package com.bustix.cargo;

/** Mirrors com.bustix.refund.CancelBookingRequest - reason is optional. */
public record CancelWaybillRequest(String reason) {
}
