package com.notificationservice.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationservice.shared.domain.NotificationChannel;
import com.notificationservice.shared.domain.NotificationState;
import com.notificationservice.shared.entity.DeadLetterNotification;
import com.notificationservice.shared.entity.Notification;
import com.notificationservice.worker.repository.DeadLetterNotificationRepository;
import com.notificationservice.worker.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles notification delivery, state transitions, and DLQ on permanent failure.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository notificationRepository;
    private final DeadLetterNotificationRepository deadLetterRepository;
    private final MockNotificationProvider mockProvider;
    private final ObjectMapper objectMapper;
    private final int maxRetries;

    public NotificationDeliveryService(NotificationRepository notificationRepository,
                                       DeadLetterNotificationRepository deadLetterRepository,
                                       MockNotificationProvider mockProvider,
                                       ObjectMapper objectMapper,
                                       @Value("${notification.max-retries:5}") int maxRetries) {
        this.notificationRepository = notificationRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.mockProvider = mockProvider;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
    }

    @Transactional
    public void process(Long notificationId) {
        var notificationOpt = notificationRepository.findByIdForUpdate(notificationId);
        if (notificationOpt.isEmpty()) {
            logger.warn("Notification not found: id={}", notificationId);
            return;
        }

        var notification = notificationOpt.get();

        // Skip if already terminal
        if (notification.getState() == NotificationState.SENT || notification.getState() == NotificationState.FAILED) {
            logger.info("Notification already terminal: id={}, state={}", notificationId, notification.getState());
            return;
        }

        // Transition to PROCESSING
        notification.setState(NotificationState.PROCESSING);
        notificationRepository.save(notification);

        try {
            Map<String, Object> payload = notification.getPayload();
            mockProvider.send(notification.getChannel(), payload);

            // Success
            notification.setState(NotificationState.SENT);
            notification.setLastError(null);
            notificationRepository.save(notification);
            logger.info("Notification sent: id={}, userId={}, channel={}", notificationId, notification.getUserId(), notification.getChannel());

        } catch (PermanentDeliveryException e) {
            handlePermanentFailure(notification, e);

        } catch (TransientDeliveryException e) {
            handleTransientFailure(notification, e);

        } catch (Exception e) {
            // Treat unknown as transient
            handleTransientFailure(notification, new TransientDeliveryException(e.getMessage(), e));
        }
    }

    private void handlePermanentFailure(Notification notification, PermanentDeliveryException e) {
        logger.warn("Permanent delivery failure: id={}, error={}", notification.getId(), e.getMessage());

        notification.setState(NotificationState.FAILED);
        notification.setLastError(e.getMessage());
        notificationRepository.save(notification);

        moveToDeadLetter(notification, e.getMessage());
    }

    private void handleTransientFailure(Notification notification, TransientDeliveryException e) {
        int retryCount = notification.getRetryCount() + 1;
        notification.setRetryCount(retryCount);
        notification.setLastError(e.getMessage());

        if (retryCount >= maxRetries) {
            logger.warn("Max retries exceeded: id={}, retryCount={}", notification.getId(), retryCount);
            notification.setState(NotificationState.FAILED);
            notificationRepository.save(notification);
            moveToDeadLetter(notification, "Max retries exceeded: " + e.getMessage());
        } else {
            notification.setState(NotificationState.RETRYING);
            notificationRepository.save(notification);
            logger.info("Transient failure, will retry: id={}, retryCount={}/{}", notification.getId(), retryCount, maxRetries);
        }
    }

    private void moveToDeadLetter(Notification notification, String failureReason) {
        var dlq = new DeadLetterNotification();
        dlq.setNotificationId(notification.getId());
        dlq.setUserId(notification.getUserId());
        dlq.setChannel(notification.getChannel());
        dlq.setPayload(notification.getPayload());
        dlq.setFailureReason(failureReason);
        dlq.setRetryCount(notification.getRetryCount());
        deadLetterRepository.save(dlq);
        logger.info("Moved to DLQ: notificationId={}, reason={}", notification.getId(), failureReason);
    }

    public Map<String, Object> parsePayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.error("Failed to parse payload: {}", payloadJson, e);
            throw new PermanentDeliveryException("Invalid payload JSON", e);
        }
    }
}
