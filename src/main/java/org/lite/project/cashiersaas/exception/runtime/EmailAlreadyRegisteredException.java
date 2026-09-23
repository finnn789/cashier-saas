package org.lite.project.cashiersaas.exception.runtime;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email sudah terdaftar.");
    }
}
