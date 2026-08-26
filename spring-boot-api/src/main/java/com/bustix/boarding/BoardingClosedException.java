package com.bustix.boarding;

/**
 * Maps to HTTP 409 in BoardingController - the "Gate Lockout" rule from
 * my-notes/ethiopian_bus_system_specs.md section 4.1: no check-in is
 * allowed once the trip's departure time has passed, no exceptions.
 */
public class BoardingClosedException extends RuntimeException {
    public BoardingClosedException(String message) {
        super(message);
    }
}
