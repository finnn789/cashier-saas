package org.lite.project.cashiersaas.exception.runtime;

public class BusinessProfileAlreadyCompletedException extends RuntimeException {

    public BusinessProfileAlreadyCompletedException() {
        this("Profil bisnis akun sudah selesai.");
    }

    public BusinessProfileAlreadyCompletedException(String message) {
        super(message);
    }
}
