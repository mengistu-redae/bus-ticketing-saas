package com.bustix.scheduling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLayoutGeneratorTest {

    @Test
    void generatesRowsOfLabeledSeatsForAnExactMultipleOfSeatsPerRow() {
        // "2x2" = 2 seats left of the aisle + 2 right = 4 seats/row.
        List<String> seats = SeatLayoutGenerator.generate(8, "2x2");

        assertThat(seats).containsExactly(
            "1A", "1B", "1C", "1D",
            "2A", "2B", "2C", "2D"
        );
    }

    @Test
    void truncatesTheLastRowWhenCapacityIsNotAnExactMultipleOfSeatsPerRow() {
        List<String> seats = SeatLayoutGenerator.generate(10, "2x2");

        assertThat(seats).hasSize(10);
        assertThat(seats).containsExactly(
            "1A", "1B", "1C", "1D",
            "2A", "2B", "2C", "2D",
            "3A", "3B"
        );
    }

    @Test
    void fallsBackToPlainNumberingWhenLayoutIsNull() {
        List<String> seats = SeatLayoutGenerator.generate(3, null);

        assertThat(seats).containsExactly("1", "2", "3");
    }

    @Test
    void fallsBackToPlainNumberingWhenLayoutDoesNotMatchTheAxBPattern() {
        List<String> seats = SeatLayoutGenerator.generate(4, "single-deck");

        assertThat(seats).containsExactly("1", "2", "3", "4");
    }

    @Test
    void fallsBackToPlainNumberingWhenSeatsPerRowExceedsAvailableColumnLetters() {
        // 20+20 = 40 seats/row, more than the 26-letter alphabet this
        // generator uses for column labels - falls back rather than
        // producing garbage or throwing.
        List<String> seats = SeatLayoutGenerator.generate(5, "20x20");

        assertThat(seats).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void producesExactlyCapacitySeatsNeverMore() {
        // Guards the "never exceed bus.capacity" invariant TripCreationService
        // relies on regardless of how seatsPerRow divides into capacity.
        for (int capacity = 1; capacity <= 41; capacity++) {
            assertThat(SeatLayoutGenerator.generate(capacity, "2x2")).hasSize(capacity);
        }
    }

    @Test
    void zeroCapacityProducesNoSeats() {
        assertThat(SeatLayoutGenerator.generate(0, "2x2")).isEmpty();
    }
}
