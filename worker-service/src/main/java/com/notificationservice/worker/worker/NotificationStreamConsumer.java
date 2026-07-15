package com.notificationservice.worker.worker;

import com.notificationservice.worker.service.NotificationDeliveryService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@EnableAsync
public class NotificationStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamConsumer.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final NotificationDeliveryService deliveryService;

    @Value("${notification.stream-name:notifications:stream}")
    private String streamName;

    @Value("${notification.consumer-group:delivery-workers}")
    private String consumerGroup;

    @Value("${notification.consumer-name:worker-1}")
    private String consumerName;

    private static final int BATCH_SIZE = 10;
    private static final Duration BLOCK_DURATION = Duration.ofSeconds(5);
    private static final long PENDING_IDLE_THRESHOLD_MS = 30_000;

    public NotificationStreamConsumer(RedisTemplate<String, String> redisTemplate,
                                       NotificationDeliveryService deliveryService) {
        this.redisTemplate = redisTemplate;
        this.deliveryService = deliveryService;
    }

    @PostConstruct
    public void init() {
        setupConsumerGroup();
        startConsuming();
    }

    private void setupConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamName, ReadOffset.from("0-0"), consumerGroup);
            log.info("Consumer group '{}' created for stream '{}'", consumerGroup, streamName);
        } catch (Exception e) {
            // Group may already exist
            log.info("Consumer group setup: {} (may already exist)", e.getMessage());
        }
    }

    @Async
    public void startConsuming() {
        log.info("Starting stream consumer: stream={}, group={}, consumer={}", streamName, consumerGroup, consumerName);

        long pollCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(consumerGroup, consumerName),
                        StreamReadOptions.empty().count(BATCH_SIZE).block(BLOCK_DURATION),
                        StreamOffset.create(streamName, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record);
                    }
                }

                // Periodically check pending messages
                pollCount++;
                if (pollCount % 20 == 0) {
                    recoverPendingMessages();
                }
            } catch (Exception e) {
                log.error("Error in stream consumer loop", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Stream consumer stopped");
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        String recordId = record.getId().getValue();
        try {
            Object notificationIdObj = record.getValue().get("notificationId");
            if (notificationIdObj == null) {
                log.warn("Record {} has no notificationId, acknowledging and skipping", recordId);
                ackRecord(record);
                return;
            }

            Long notificationId = Long.parseLong(notificationIdObj.toString());
            log.info("Processing record id={}, notificationId={}", recordId, notificationId);

            deliveryService.process(notificationId);
            ackRecord(record);
        } catch (Exception e) {
            log.error("Failed to process record id={}: {}", recordId, e.getMessage(), e);
            // Do NOT ack - leave as pending for recovery
        }
    }

    private void ackRecord(MapRecord<String, Object, Object> record) {
        try {
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, record.getId());
        } catch (Exception e) {
            log.error("Failed to acknowledge record id={}: {}", record.getId().getValue(), e.getMessage());
        }
    }

    private void recoverPendingMessages() {
        try {
            PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
                    streamName,
                    Consumer.from(consumerGroup, consumerName),
                    Range.unbounded(),
                    BATCH_SIZE
            );

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            for (PendingMessage pending : pendingMessages) {
                long idleMs = pending.getElapsedTimeSinceLastDelivery().toMillis();
                if (idleMs >= PENDING_IDLE_THRESHOLD_MS) {
                    log.info("Claiming idle pending message id={}, idleMs={}", pending.getIdAsString(), idleMs);
                    List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                            streamName,
                            consumerGroup,
                            consumerName,
                            Duration.ofMillis(PENDING_IDLE_THRESHOLD_MS),
                            pending.getId()
                    );
                    if (claimed != null) {
                        for (MapRecord<String, Object, Object> record : claimed) {
                            processRecord(record);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error during pending message recovery: {}", e.getMessage());
        }
    }
}
