package com.bustix.cargo;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Year;

/**
 * Generates the human-facing waybill_number, mirroring
 * com.bustix.booking.TicketNumberGenerator's shape (operator-derived
 * prefix + year + a bounded-retry-checked random number) - e.g.
 * "DBC-CARGO-2026-4763827" for an operator whose ticket prefix is "DBC",
 * per the BRD's own "SBC-990812"-style example
 * (my-notes/ethiopian_bus_system_specs.md section 3.2), extended with a
 * literal "CARGO" segment so a waybill number is never visually confusable
 * with a passenger ticket number.
 *
 * Kept as its own small generator rather than factored out into a shared
 * base with TicketNumberGenerator - this codebase doesn't have a shared
 * "generator" abstraction anywhere else either, and the two check
 * uniqueness against different repositories/tables.
 */
@Component
public class WaybillNumberGenerator {

    private static final int MAX_ATTEMPTS = 5;
    private static final int WAYBILL_NUMBER_DIGITS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CargoWaybillRepository cargoWaybillRepository;

    public WaybillNumberGenerator(CargoWaybillRepository cargoWaybillRepository) {
        this.cargoWaybillRepository = cargoWaybillRepository;
    }

    public String nextWaybillNumber(String operatorName) {
        String prefix = operatorPrefix(operatorName);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int number = RANDOM.nextInt(10_000_000); // 0..9,999,999
            String candidate = prefix + "-CARGO-" + Year.now() + "-"
                    + String.format("%0" + WAYBILL_NUMBER_DIGITS + "d", number);
            if (!cargoWaybillRepository.existsByWaybillNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique waybill number after " + MAX_ATTEMPTS + " attempts");
    }

    /** First letter of each word in the operator name, e.g. "Selam Bus Line" -> "SBL", capped at 4 chars. Falls back to "CG". */
    private String operatorPrefix(String operatorName) {
        if (operatorName == null || operatorName.isBlank()) {
            return "CG";
        }
        StringBuilder prefix = new StringBuilder();
        for (String word : operatorName.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                prefix.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        if (prefix.isEmpty()) {
            return "CG";
        }
        return prefix.substring(0, Math.min(prefix.length(), 4));
    }
}
