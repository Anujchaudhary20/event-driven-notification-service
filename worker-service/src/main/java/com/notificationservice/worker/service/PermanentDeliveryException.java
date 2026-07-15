package com.notificationservice.worker.service;

public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
