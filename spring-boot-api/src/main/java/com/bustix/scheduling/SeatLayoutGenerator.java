package com.bustix.scheduling;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bus's capacity + seat_layout (e.g. "2x2") into row-labeled seat
 * numbers ("1A","1B","1C","1D","2A",...). seat_layout has been in the schema
 * since V1 (see the comment on seats.seat_class for the same "reserved for
 * later, unused until now" spirit) but nothing generated actual seats from
 * it until trip creation needed to.
 *
 * "AxB" means A seats left of the aisle, B right of it, so seats per row =
 * A+B (a plain "4" with no "x" is treated as unparseable, not a 4-wide
 * single block - callers relying on that would need a different layout
 * string). An unparseable layout falls back to a single block of plain
 * numbered seats ("1".."capacity") rather than failing trip creation over
 * cosmetic seat numbering.
 */
final class SeatLayoutGenerator {

    private static final Pattern LAYOUT_PATTERN = Pattern.compile("^(\\d+)x(\\d+)$");
    private static final String COLUMN_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private SeatLayoutGenerator() {
    }

    static List<String> generate(int capacity, String seatLayout) {
        List<String> seatNumbers = new ArrayList<>(capacity);
        Matcher matcher = seatLayout != null ? LAYOUT_PATTERN.matcher(seatLayout.trim()) : null;

        if (matcher != null && matcher.matches()) {
            int seatsPerRow = Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2));
            if (seatsPerRow > 0 && seatsPerRow <= COLUMN_LETTERS.length()) {
                int row = 1;
                while (seatNumbers.size() < capacity) {
                    for (int col = 0; col < seatsPerRow && seatNumbers.size() < capacity; col++) {
                        seatNumbers.add(row + String.valueOf(COLUMN_LETTERS.charAt(col)));
                    }
                    row++;
                }
                return seatNumbers;
            }
        }

        for (int i = 1; i <= capacity; i++) {
            seatNumbers.add(String.valueOf(i));
        }
        return seatNumbers;
    }
}
