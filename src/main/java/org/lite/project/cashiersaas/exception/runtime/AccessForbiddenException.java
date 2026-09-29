package org.lite.project.cashiersaas.exception.runtime;

import org.lite.project.cashiersaas.exception.ErrorMessage;

public class AccessForbiddenException extends RuntimeException {
    public AccessForbiddenException() {
        this(ErrorMessage.ACCESS_FORBIDDEN.getMessage());
    }

    public AccessForbiddenException(String message) {
        super(message);
    }
}
