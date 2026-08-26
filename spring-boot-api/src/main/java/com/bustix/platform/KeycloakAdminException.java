package com.bustix.platform;

/** Maps to HTTP 502 in PlatformController - a call to Keycloak's Admin REST API failed. */
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
