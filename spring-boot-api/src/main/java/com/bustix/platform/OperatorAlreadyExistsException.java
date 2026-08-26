package com.bustix.platform;

/** Maps to HTTP 409 in PlatformController - an operator already exists for the given org alias. */
public class OperatorAlreadyExistsException extends RuntimeException {
    public OperatorAlreadyExistsException(String message) {
        super(message);
    }
}
