package com.bustix.refund;

/** reason is optional - staff aren't required to give one to process a cancellation. */
public record CancelBookingRequest(String reason) {
}
