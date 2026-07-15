package com.notificationservice.worker.worker;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.notificationservice.worker.service.NotificationDeliveryService;

/**
 * Consumes notifications from Redis Stream using consumer group.
 * Creates stream and group on startup if missing.
 * Processes messages and ACKs on success or permanent failure.
 */
@Component
public class NotificationStreamConsumer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(NotificationStreamConsumer.class);

    private final String streamName;
    private final String consumerGroup;
    private final String consumerName;
    private final NotificationDeliveryService deliveryService;
    private final RedisConnectionFactory connectionFactory;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 10;

    public NotificationStreamConsumer(@Qualifier("notificationStreamName") String streamName,
                                     @Qualifier("consumerGroup") String consumerGroup,
                                     @Qualifier("consumerName") String consumerName,
                                     NotificationDeliveryService deliveryService,
                                     RedisConnectionFactory connectionFactory,
                                     @Qualifier("redisStreamTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.streamName = streamName;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.deliveryService = deliveryService;
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) {
        ensureStreamAndGroup();
        startConsumptionLoop();
    }

    private void ensureStreamAndGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamName, ReadOffset.from("0-0"), consumerGroup);
            logger.info("Created consumer group {} for stream {}", consumerGroup, streamName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                logger.info("Consumer group {} already exists for stream {}", consumerGroup, streamName);
            } else {
                logger.info("Stream/group setup: {}", e.getMessage());
            }
        }
    }

    private void startConsumptionLoop() {
        logger.info("Starting consumption loop: stream={}, group={}, consumer={}", streamName, consumerGroup, consumerName);

        while (true) {
            try {
                consumeNewMessages();
                claimPendingMessages();
            } catch (Exception e) {
    if (e.getMessage() != null && e.getMessage().contains("timed out")) {
        // Normal idle polling – do nothing or log DEBUG
        logger.debug("Redis stream poll timeout (idle)");
    } else {
        logger.error("Redis stream error", e);
    }
}

            sleep(Duration.ofMillis(500));
        }
    }

    private void consumeNewMessages() {
        try {
            var streamOffset = StreamOffset.create(streamName, ReadOffset.lastConsumed());
            var consumer = Consumer.from(consumerGroup, consumerName);
            var readRequest = StreamReadOptions.empty().count(BATCH_SIZE).block(POLL_TIMEOUT);

            var records = redisTemplate.opsForStream().read(consumer, readRequest, streamOffset);

            if (records != null && !records.isEmpty()) {
                for (var record : records) {
                    processRecord(record);
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading stream: {}", e.getMessage());
        }
    }

    private void claimPendingMessages() {
        try {
            var pending = redisTemplate.opsForStream().pending(streamName, consumerGroup, org.springframework.data.domain.Range.unbounded(), BATCH_SIZE);
            if (pending == null || pending.isEmpty()) return;

            for (var entry : pending) {
                String messageId = entry.getIdAsString();
                long idleTime = entry.getElapsedTimeSinceLastDelivery().toMillis();

                // Exponential backoff: 1s, 2s, 4s, 8s, 16s (idle time must exceed backoff)
                long minIdleMs = 1000L * (1 << Math.min(entry.getTotalDeliveryCount(), 5));
                if (idleTime < minIdleMs) continue;

                try {
                    var claimed = redisTemplate.opsForStream().claim(streamName, consumerGroup, consumerName, Duration.ofMillis(minIdleMs), RecordId.of(messageId));
                    if (claimed != null && !claimed.isEmpty()) {
                        for (var record : claimed) {
                            processRecord(record);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error claiming pending message {}: {}", messageId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error checking pending: {}", e.getMessage());
        }
    }

    private void processRecord(org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        @SuppressWarnings("unchecked")
        Map<Object, Object> value = (Map<Object, Object>) record.getValue();

        String notificationIdStr = getString(value, "notificationId");
        if (notificationIdStr == null) {
            logger.warn("Missing notificationId in message: {}", messageId);
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, messageId);
            return;
        }

        long notificationId;
        try {
            notificationId = Long.parseLong(notificationIdStr);
        } catch (NumberFormatException e) {
            logger.warn("Invalid notificationId in message: {}", notificationIdStr);
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, messageId);
            return;
        }

        logger.info("Processing message: messageId={}, notificationId={}", messageId, notificationId);

        try {
            deliveryService.process(notificationId);

            // ACK on success; on failure, delivery service handles state - we still ACK to remove from stream
            // (DLQ is already written; keeping in stream would cause infinite reprocessing)
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, messageId);

        } catch (Exception e) {
            logger.error("Error processing notification {}: {}", notificationId, e.getMessage());
            // Don't ACK - message stays in pending for retry via claimPendingMessages
        }
    }

    private String getString(Map<Object, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
