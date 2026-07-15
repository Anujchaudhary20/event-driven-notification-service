package com.notificationservice.worker.service;

/**
 * Transient failure: timeout, 5xx, connection reset. Safe to retry.
 */
public class TransientDeliveryException extends RuntimeException {

    public TransientDeliveryException(String message) {
        super(message);
    }

    public TransientDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
