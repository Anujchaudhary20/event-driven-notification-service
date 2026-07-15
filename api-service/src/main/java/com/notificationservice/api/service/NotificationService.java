package com.notificationservice.api.service;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationservice.api.repository.NotificationRepository;
import com.notificationservice.shared.domain.NotificationState;
import com.notificationservice.shared.dto.SendNotificationRequest;
import com.notificationservice.shared.dto.SendNotificationResponse;
import com.notificationservice.shared.entity.Notification;

/**
 * Notification acceptance service. Handles idempotency, rate limiting, persistence, and stream publish.
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final RedisTemplate<String, Object> redisStreamTemplate;
    private final String streamName;
    private final int rateLimitPerUserPerMinute;
    private final ObjectMapper objectMapper;

    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    public NotificationService(NotificationRepository notificationRepository,
                               @Qualifier("redisStreamTemplate") RedisTemplate<String, Object> redisStreamTemplate,
                               @Qualifier("notificationStreamName") String streamName,
                               @Value("${notification.rate-limit.per-user-per-minute:10}") int rateLimitPerUserPerMinute,
                               ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.redisStreamTemplate = redisStreamTemplate;
        this.streamName = streamName;
        this.rateLimitPerUserPerMinute = rateLimitPerUserPerMinute;
        this.objectMapper = objectMapper;
    }

    /**
     * Accept notification request. Idempotent: same idempotencyKey returns same response.
     * Returns 202 Accepted with notification ID.
     */
    @Transactional
    public SendNotificationResponse send(SendNotificationRequest request) {
        // 1. Idempotency check: if key exists, return existing (exactly-once guarantee)
        var existing = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            logger.info("Idempotency hit: idempotencyKey={}, notificationId={}", request.getIdempotencyKey(), existing.get().getId());
            return new SendNotificationResponse(existing.get().getId(), existing.get().getState().name());
        }

        // 2. Rate limit: per-user per minute
        if (!checkRateLimit(request.getUserId())) {
            throw new RateLimitExceededException("Rate limit exceeded for user " + request.getUserId());
        }

        // 3. Persist notification with PENDING state
        var notification = new Notification();
        notification.setIdempotencyKey(request.getIdempotencyKey());
        notification.setUserId(request.getUserId());
        notification.setChannel(request.getChannel());
        notification.setPayload(request.getPayload());
        notification.setState(NotificationState.PENDING);
        notification.setRetryCount(0);

        try {
            notification = notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            // Unique constraint on idempotency_key: concurrent duplicate, return existing
            var dup = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (dup.isPresent()) {
                return new SendNotificationResponse(dup.get().getId(), dup.get().getState().name());
            }
            throw e;
        }

        // 4. Increment rate limit counter
        incrementRateLimit(request.getUserId());

        // 5. Publish event to Redis Stream
        publishToStream(notification);

        logger.info("Notification accepted: id={}, userId={}, channel={}", notification.getId(), notification.getUserId(), notification.getChannel());
        return new SendNotificationResponse(notification.getId(), NotificationState.PENDING.name());
    }

    private boolean checkRateLimit(String userId) {
        String key = RATE_LIMIT_KEY_PREFIX + userId;
        var value = redisStreamTemplate.opsForValue().get(key);
        if (value == null) return true;
        int count = Integer.parseInt(value.toString());
        return count < rateLimitPerUserPerMinute;
    }

    private void incrementRateLimit(String userId) {
        String key = RATE_LIMIT_KEY_PREFIX + userId;
        var ops = redisStreamTemplate.opsForValue();
        Long count = ops.increment(key);
        if (count != null && count == 1) {
            redisStreamTemplate.expire(key, RATE_LIMIT_WINDOW);
        }
    }

    private void publishToStream(Notification notification) {
        try {
            Map<String, String> record = Map.of(
                    "notificationId", String.valueOf(notification.getId()),
                    "userId", notification.getUserId(),
                    "channel", notification.getChannel().name(),
                    "payload", objectMapper.writeValueAsString(notification.getPayload())
            );
            redisStreamTemplate.opsForStream().add(streamName, record);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize payload for stream: notificationId={}", notification.getId(), e);
            throw new RuntimeException("Failed to publish notification event", e);
        }
    }
}
