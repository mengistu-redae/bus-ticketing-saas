package com.bustix.dashboard;

import java.math.BigDecimal;
import java.util.UUID;

/** A route's confirmed-booking count and revenue over the selected window - "top routes" leaderboard row. */
public record RouteStat(UUID routeId, String routeName, long bookings, BigDecimal revenue) {
}
