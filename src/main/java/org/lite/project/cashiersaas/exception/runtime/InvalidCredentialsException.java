package org.lite.project.cashiersaas.exception.runtime;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email atau kata sandi tidak valid.");
    }
}
