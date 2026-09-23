package org.lite.project.cashiersaas.exception.runtime;

import org.springframework.dao.DataIntegrityViolationException;

public class DataIntegrityViolationRuntimeException extends RuntimeException {

    public DataIntegrityViolationRuntimeException(DataIntegrityViolationException cause) {
        super("Data tidak dapat disimpan karena melanggar aturan integritas.", cause);
    }
}
