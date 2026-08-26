package com.bustix.cargo;

/**
 * Thrown when a waybill's description matches an entry in
 * bustix.cargo.prohibited-items (see ProhibitedItemsChecker). Mapped to 400
 * in CargoWaybillController - this is a content-safety rejection, not a
 * conflict with existing state.
 */
public class ProhibitedItemException extends RuntimeException {
    public ProhibitedItemException(String message) {
        super(message);
    }
}
