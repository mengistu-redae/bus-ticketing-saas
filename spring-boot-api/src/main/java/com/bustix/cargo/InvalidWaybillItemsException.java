package com.bustix.cargo;

/** Maps to HTTP 400 in CargoWaybillController - a PATCH explicitly sent an empty items list (a shipment must have at least one item; null instead means "leave items alone"). */
public class InvalidWaybillItemsException extends RuntimeException {
    public InvalidWaybillItemsException(String message) {
        super(message);
    }
}
