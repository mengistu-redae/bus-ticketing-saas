package com.bustix.cargo;

/**
 * Thrown by CargoWaybillService.collect when the ID presented at pickup
 * doesn't match consignee_id_number on file - mirrors
 * com.bustix.boarding.IdentityMismatchException's role for passenger
 * boarding, kept as its own class rather than reused directly since a
 * consignee pickup and a passenger boarding are distinct concepts even
 * though the check itself is shaped the same way. Mapped to 409.
 */
public class ConsigneeIdentityMismatchException extends RuntimeException {
    public ConsigneeIdentityMismatchException(String message) {
        super(message);
    }
}
