package com.bustix.dashboard;

/**
 * The rolling window a dashboard's analytics cover, chosen by the caller via
 * {@code ?period=7d|30d|90d} (default 30d). The "previous" window of equal
 * length immediately before it backs the period-over-period deltas.
 */
public enum DashboardPeriod {

    D7(7, "7d"),
    D30(30, "30d"),
    D90(90, "90d");

    private final int days;
    private final String wire;

    DashboardPeriod(int days, String wire) {
        this.days = days;
        this.wire = wire;
    }

    public int days() {
        return days;
    }

    public String wire() {
        return wire;
    }

    /** Lenient - anything unrecognized falls back to the 30-day default rather than 400ing. */
    public static DashboardPeriod parse(String raw) {
        if (raw != null) {
            for (DashboardPeriod p : values()) {
                if (p.wire.equalsIgnoreCase(raw.trim())) {
                    return p;
                }
            }
        }
        return D30;
    }
}
