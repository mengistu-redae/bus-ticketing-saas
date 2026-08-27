package com.bustix.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gap-filled daily time series for the selected window - one entry per
 * calendar day (UTC), zeros included, so the frontend can plot a continuous
 * axis without stitching gaps itself. Parallel arrays rather than a list of
 * points: smaller on the wire and it maps straight onto a charting library's
 * {@code dataKey} model.
 */
public record DailySeries(
        List<String> days,
        List<Long> bookings,
        List<BigDecimal> revenue,
        List<Long> cancellations) {
}
