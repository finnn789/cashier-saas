package org.lite.project.cashiersaas.exception.runtime;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Data tidak ditemukan.");
    }
}
