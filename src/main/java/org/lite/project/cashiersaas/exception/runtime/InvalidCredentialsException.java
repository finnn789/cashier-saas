package org.lite.project.cashiersaas.exception.runtime;

import org.lite.project.cashiersaas.exception.ErrorMessage;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        this(ErrorMessage.INVALID_CREDENTIALS.getMessage());
    }

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
