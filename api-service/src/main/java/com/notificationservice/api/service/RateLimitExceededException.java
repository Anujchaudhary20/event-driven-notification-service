package com.notificationservice.api.service;

public class RateLimitExceededException extends RuntimeException {

    private final String userId;

    public RateLimitExceededException(String message) {
        super(message);
        this.userId = null;
    }

    public RateLimitExceededException(String message, String userId) {
        super(message);
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
