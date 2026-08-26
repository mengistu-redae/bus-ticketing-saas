package com.bustix.boarding;

/**
 * Maps to HTTP 409 in BoardingController - the "Validation Engine" rule
 * from my-notes/ethiopian_bus_system_specs.md section 4.1: the ID
 * presented at the gate doesn't match the ID on file for that seat's
 * passenger (or none is on file to check against at all).
 */
public class IdentityMismatchException extends RuntimeException {
    public IdentityMismatchException(String message) {
        super(message);
    }
}
