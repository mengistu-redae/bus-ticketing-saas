package com.bustix.dashboard;

import java.math.BigDecimal;

/**
 * One slice of a categorical breakdown (booking channel, booking/cargo status,
 * payment method). {@code amount} is the money dimension where one applies
 * (revenue for channel/status, collected total for payment method) and 0
 * otherwise.
 */
public record Breakdown(String key, long count, BigDecimal amount) {
}
