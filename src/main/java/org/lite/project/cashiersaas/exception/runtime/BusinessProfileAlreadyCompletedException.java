package org.lite.project.cashiersaas.exception.runtime;

public class BusinessProfileAlreadyCompletedException extends RuntimeException {

    public BusinessProfileAlreadyCompletedException() {
        super("Profil bisnis akun sudah selesai.");
    }
}
