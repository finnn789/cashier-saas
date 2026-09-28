package org.lite.project.cashiersaas.exception.runtime;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        this("Data tidak ditemukan.");
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
