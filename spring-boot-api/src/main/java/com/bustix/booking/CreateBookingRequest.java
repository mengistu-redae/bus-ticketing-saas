package com.bustix.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public record CreateBookingRequest(
    @NotNull UUID tripId,
    @NotEmpty @Valid List<PassengerSeat> passengers,
    @NotNull String idempotencyKey
) {
    /**
     * One seat + the passenger it's for - a real ticket is issued per named
     * passenger (see V3__ticketing_details.sql), not just a bare seat id.
     * phone/id fields are optional: an ID document especially is often
     * checked at the terminal at boarding, not always known at booking
     * time - see my-notes/ethiopian_bus_system_specs.md section 2.2.
     *
     * `age`/`infants` implement that same file's section 4.1 age rules:
     * age >= 3 is a normal (full-fare, this-seat) adult passenger - age
     * itself is optional metadata here, not fare-affecting in v1's flat
     * pricing. `infants` (age < 3 each, enforced by Infant.age's own
     * @Max) ride free on this seated passenger's lap rather than getting a
     * seat of their own - see BookingInfant's javadoc for why they don't
     * appear in `passengers` as their own entries.
     */
    public record PassengerSeat(
        @NotNull UUID seatId,
        @NotBlank String passengerName,
        /** E.164 Ethiopian format, e.g. +251911234567 or +251711234567 - validated only when present. */
        @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "Phone number must be E.164 Ethiopian format, e.g. +251911234567")
        String passengerPhone,
        String passengerIdNumber,
        /** Which kind of document passengerIdNumber is from - see IdentityDocumentType. */
        IdentityDocumentType passengerIdType,
        @Min(0) Integer age,
        @Valid List<Infant> infants
    ) {
        /** Kept for callers built before age/infants existed - defaults both to "none supplied". */
        public PassengerSeat(
                UUID seatId,
                String passengerName,
                String passengerPhone,
                String passengerIdNumber,
                IdentityDocumentType passengerIdType) {
            this(seatId, passengerName, passengerPhone, passengerIdNumber, passengerIdType, null, List.of());
        }

        /** Normalizes a null `infants` (e.g. omitted from the JSON body entirely) to an empty list rather than forcing every caller to null-check it. */
        public PassengerSeat {
            if (infants == null) {
                infants = List.of();
            }
        }

        /** One infant (age < 3 - see my-notes/ethiopian_bus_system_specs.md section 4.1) riding on this seat's passenger's lap. Always free. */
        public record Infant(
            @NotBlank String name,
            @Min(0) @Max(2) int age
        ) {
        }
    }
}
