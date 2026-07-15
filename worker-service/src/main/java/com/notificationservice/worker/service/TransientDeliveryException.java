package com.notificationservice.worker.service;

public class TransientDeliveryException extends RuntimeException {

    public TransientDeliveryException(String message) {
        super(message);
    }

    public TransientDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
