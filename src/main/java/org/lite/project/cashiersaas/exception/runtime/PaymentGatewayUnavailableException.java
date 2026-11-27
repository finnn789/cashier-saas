package org.lite.project.cashiersaas.exception.runtime;

/** The payment provider could not be reached or answered with an error. */
public class PaymentGatewayUnavailableException extends RuntimeException {
    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }
}
