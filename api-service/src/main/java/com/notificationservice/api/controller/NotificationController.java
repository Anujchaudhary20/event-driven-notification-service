package com.notificationservice.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.notificationservice.api.service.NotificationService;
import com.notificationservice.api.service.RateLimitExceededException;
import com.notificationservice.shared.dto.SendNotificationRequest;
import com.notificationservice.shared.dto.SendNotificationResponse;

import jakarta.validation.Valid;

/**
 * REST API for notification acceptance. Returns 202 Accepted immediately; delivery is asynchronous.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Accept notification request. Idempotent via Idempotency-Key header or body.
     * Returns 202 Accepted with notification ID.
     */
    @PostMapping("/send")
    public ResponseEntity<SendNotificationResponse> send(@Valid @RequestBody SendNotificationRequest request,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String headerKey) {
        // Allow idempotency key from header (preferred) or body
        if (headerKey != null && !headerKey.isBlank()) {
            request.setIdempotencyKey(headerKey.trim());
        }
        SendNotificationResponse response = notificationService.send(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("RATE_LIMIT_EXCEEDED", ex.getMessage()));
    }

    public record ApiError(String code, String message) {}
}
