package com.notificationservice.api.controller;

import com.notificationservice.api.service.NotificationService;
import com.notificationservice.api.service.RateLimitExceededException;
import com.notificationservice.shared.dto.SendNotificationRequest;
import com.notificationservice.shared.dto.SendNotificationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody SendNotificationRequest request) {

        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            request.setIdempotencyKey(idempotencyKeyHeader);
        }

        try {
            SendNotificationResponse response = notificationService.send(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (RateLimitExceededException e) {
            Map<String, String> error = Map.of(
                    "code", "RATE_LIMIT_EXCEEDED",
                    "message", e.getMessage()
            );
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
        }
    }
}
