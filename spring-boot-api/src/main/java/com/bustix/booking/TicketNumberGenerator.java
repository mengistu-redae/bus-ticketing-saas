package com.bustix.booking;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Year;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates the human-facing ticket number and booking reference (PNR)
 * shown to a passenger, distinct from Booking.id - see
 * V3__ticketing_details.sql. Both columns are NOT NULL, so these are
 * generated before the booking's first save rather than derived from its
 * own persisted id (which would need a second save just to fill them in).
 *
 * Ticket number format follows the pattern from
 * my-notes/ethiopian_bus_system_specs.md section 2.1 (e.g. "SB-2026-7894561"
 * for Selam Bus): an operator-derived prefix, the current year, and a
 * 7-digit number. Booking ref stays a bare 6-char code (that file's
 * "SM76TR"-style PNR) - checked for uniqueness with a bounded retry rather
 * than trusted from randomness alone, since a 7-digit/6-char space is small
 * enough at scale that a blind collision isn't negligible.
 */
@Component
public class TicketNumberGenerator {

    private static final int MAX_ATTEMPTS = 5;
    private static final int TICKET_NUMBER_DIGITS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookingRepository;

    public TicketNumberGenerator(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    public String nextTicketNumber(String operatorName) {
        String prefix = operatorPrefix(operatorName);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int number = RANDOM.nextInt(10_000_000); // 0..9,999,999
            String candidate = prefix + "-" + Year.now() + "-" + String.format("%0" + TICKET_NUMBER_DIGITS + "d", number);
            if (!bookingRepository.existsByTicketNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique ticket number after " + MAX_ATTEMPTS + " attempts");
    }

    public String nextBookingRef() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomHex(6);
            if (!bookingRepository.existsByBookingRef(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking reference after " + MAX_ATTEMPTS + " attempts");
    }

    /** First letter of each word in the operator name, e.g. "Selam Bus Line" -> "SBL", capped at 4 chars. Falls back to "BK". */
    private String operatorPrefix(String operatorName) {
        if (operatorName == null || operatorName.isBlank()) {
            return "BK";
        }
        StringBuilder prefix = new StringBuilder();
        for (String word : operatorName.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                prefix.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        if (prefix.isEmpty()) {
            return "BK";
        }
        return prefix.substring(0, Math.min(prefix.length(), 4));
    }

    private String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length).toUpperCase(Locale.ROOT);
    }
}
