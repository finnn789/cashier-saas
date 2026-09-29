package org.lite.project.cashiersaas.exception.runtime;

import org.lite.project.cashiersaas.exception.ErrorMessage;

public class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException() {
        this(ErrorMessage.AUTHENTICATION_INVALID.getMessage());
    }

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
