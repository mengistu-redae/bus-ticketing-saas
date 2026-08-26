package com.bustix.booking;

/**
 * The kind of ID a passenger presents at boarding - see
 * my-notes/ethiopian_bus_system_specs.md section 2.2. Stored alongside
 * BookingSeat.passengerIdNumber (the document's actual number); this enum
 * only records which kind of document that number is from.
 */
public enum IdentityDocumentType {
    KEBELE_ID,
    DIGITAL_ID,
    PASSPORT,
    DRIVERS_LICENSE
}
