package com.notificationservice.worker.service;

/**
 * Permanent failure: 4xx, invalid config, invalid address. Do not retry; move to DLQ.
 */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
