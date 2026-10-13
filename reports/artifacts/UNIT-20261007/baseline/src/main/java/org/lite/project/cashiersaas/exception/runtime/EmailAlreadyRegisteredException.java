package org.lite.project.cashiersaas.exception.runtime;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        this("Email sudah terdaftar.");
    }

    public EmailAlreadyRegisteredException(String message) {
        super(message);
    }
}
