package com.bustix.dashboard;

import java.math.BigDecimal;

/** Money counterpart of {@link TrendCount} - a period total vs. the prior window. */
public record TrendMoney(BigDecimal current, BigDecimal previous, double deltaPct) {

    static TrendMoney of(BigDecimal current, BigDecimal previous) {
        BigDecimal c = current == null ? BigDecimal.ZERO : current;
        BigDecimal p = previous == null ? BigDecimal.ZERO : previous;
        return new TrendMoney(c, p, TrendCount.pct(c.doubleValue(), p.doubleValue()));
    }
}
