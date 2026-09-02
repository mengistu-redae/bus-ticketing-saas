package com.bustix.api.v1;

import java.util.UUID;

/**
 * One seat on a trip's seat map. {@code status} is {@code open} or
 * {@code booked}; a partner needs the {@code id} to name a seat in
 * {@code POST /v1/bookings}.
 */
public record SeatView(
    UUID id,
    String seatNo,
    String seatClass,
    String status
) {
}
