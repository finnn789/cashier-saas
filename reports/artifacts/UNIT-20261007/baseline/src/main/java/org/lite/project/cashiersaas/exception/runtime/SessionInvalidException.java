package org.lite.project.cashiersaas.exception.runtime;

import org.lite.project.cashiersaas.exception.ErrorMessage;

public class SessionInvalidException extends AuthenticationRequiredException {
    public SessionInvalidException() {
        super(ErrorMessage.SESSION_INVALID.getMessage());
    }

    public SessionInvalidException(String message) {
        super(message);
    }
}
