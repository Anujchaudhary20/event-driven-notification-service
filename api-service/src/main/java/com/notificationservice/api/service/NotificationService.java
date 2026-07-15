package com.notificationservice.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationservice.api.repository.NotificationRepository;
import com.notificationservice.shared.domain.NotificationState;
import com.notificationservice.shared.dto.SendNotificationRequest;
import com.notificationservice.shared.dto.SendNotificationResponse;
import com.notificationservice.shared.entity.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.stream-name:notifications:stream}")
    private String streamName;

    @Value("${notification.rate-limit.per-user-per-minute:10}")
    private int rateLimitPerUserPerMinute;

    public NotificationService(NotificationRepository notificationRepository,
                                RedisTemplate<String, String> redisTemplate,
                                ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SendNotificationResponse send(SendNotificationRequest request) {
        // Idempotency check
        Optional<Notification> existing = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            Notification n = existing.get();
            log.info("Idempotency hit for key={}, notificationId={}", request.getIdempotencyKey(), n.getId());
            return new SendNotificationResponse(n.getId(), n.getState());
        }

        // Rate limit check
        if (!checkRateLimit(request.getUserId())) {
            log.warn("Rate limit exceeded for userId={}", request.getUserId());
            throw new RateLimitExceededException("Rate limit exceeded for user " + request.getUserId());
        }

        // Persist notification
        Notification notification = new Notification();
        notification.setIdempotencyKey(request.getIdempotencyKey());
        notification.setUserId(request.getUserId());
        notification.setChannel(request.getChannel());
        notification.setPayload(request.getPayload());
        notification.setState(NotificationState.PENDING);
        notification.setRetryCount(0);

        try {
            notification = notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate – re-read existing row
            Notification existingRow = notificationRepository
                    .findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> e);
            log.info("Concurrent idempotency hit for key={}, notificationId={}", request.getIdempotencyKey(), existingRow.getId());
            return new SendNotificationResponse(existingRow.getId(), existingRow.getState());
        }

        // Increment rate limit
        incrementRateLimit(request.getUserId());

        // Publish to Redis Stream
        publishToStream(notification);

        log.info("Accepted notification id={} for userId={} channel={}", notification.getId(), notification.getUserId(), notification.getChannel());
        return new SendNotificationResponse(notification.getId(), notification.getState());
    }

    private boolean checkRateLimit(String userId) {
        String key = "rate_limit:" + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return true;
        }
        return Integer.parseInt(value) < rateLimitPerUserPerMinute;
    }

    private void incrementRateLimit(String userId) {
        String key = "rate_limit:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
    }

    private void publishToStream(Notification notification) {
        try {
            String payloadJson = objectMapper.writeValueAsString(notification.getPayload());
            Map<String, String> fields = Map.of(
                    "notificationId", notification.getId().toString(),
                    "userId", notification.getUserId(),
                    "channel", notification.getChannel().name(),
                    "payload", payloadJson
            );
            redisTemplate.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(streamName));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for notificationId={}", notification.getId(), e);
            throw new RuntimeException("Failed to publish notification to stream", e);
        }
    }
}
