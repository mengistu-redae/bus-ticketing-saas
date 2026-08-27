package com.bustix.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A count for the selected period alongside the equal-length window before it,
 * plus the percentage change between them. {@code deltaPct} is 100.0 when
 * something appeared from a zero baseline, 0.0 when both are zero.
 */
public record TrendCount(long current, long previous, double deltaPct, long cancelledCurrent) {

    static TrendCount of(long current, long previous) {
        return new TrendCount(current, previous, pct(current, previous), 0);
    }

    static TrendCount of(long current, long previous, long cancelledCurrent) {
        return new TrendCount(current, previous, pct(current, previous), cancelledCurrent);
    }

    static double pct(double current, double previous) {
        if (previous == 0) {
            return current > 0 ? 100.0 : 0.0;
        }
        return BigDecimal.valueOf((current - previous) / previous * 100.0)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
