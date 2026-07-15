package com.notificationservice.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository notificationRepository;
    private final DeadLetterNotificationRepository deadLetterRepository;
    private final MockNotificationProvider provider;
    private final ObjectMapper objectMapper;

    @Value("${notification.max-retries:5}")
    private int maxRetries;

    public NotificationDeliveryService(NotificationRepository notificationRepository,
                                        DeadLetterNotificationRepository deadLetterRepository,
                                        MockNotificationProvider provider,
                                        ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(Long notificationId) {
        Optional<Notification> optNotification = notificationRepository.findByIdWithLock(notificationId);
        if (optNotification.isEmpty()) {
            log.warn("Notification not found: id={}", notificationId);
            return;
        }

        Notification notification = optNotification.get();

        if (notification.getState() == NotificationState.SENT
                || notification.getState() == NotificationState.FAILED) {
            log.info("Notification already in terminal state={}, skipping id={}", notification.getState(), notificationId);
            return;
        }

        notification.setState(NotificationState.PROCESSING);
        notificationRepository.save(notification);

        try {
            provider.send(notification.getChannel(), notification.getPayload());
            notification.setState(NotificationState.SENT);
            notification.setLastError(null);
            notificationRepository.save(notification);
            log.info("Notification delivered successfully: id={}", notificationId);
        } catch (PermanentDeliveryException e) {
            log.error("Permanent delivery failure for notificationId={}: {}", notificationId, e.getMessage());
            notification.setState(NotificationState.FAILED);
            notification.setLastError(truncate(e.getMessage(), 2000));
            notificationRepository.save(notification);
            insertDeadLetter(notification, e.getMessage());
        } catch (Exception e) {
            // Transient or unknown failure
            int newRetryCount = notification.getRetryCount() + 1;
            notification.setRetryCount(newRetryCount);
            notification.setLastError(truncate(e.getMessage(), 2000));
            log.warn("Transient delivery failure for notificationId={}, retryCount={}: {}", notificationId, newRetryCount, e.getMessage());

            if (newRetryCount >= maxRetries) {
                log.error("Max retries reached for notificationId={}, moving to FAILED", notificationId);
                notification.setState(NotificationState.FAILED);
                notificationRepository.save(notification);
                insertDeadLetter(notification, e.getMessage());
            } else {
                notification.setState(NotificationState.RETRYING);
                notificationRepository.save(notification);
            }
        }
    }

    private void insertDeadLetter(Notification notification, String reason) {
        DeadLetterNotification dlq = new DeadLetterNotification();
        dlq.setNotificationId(notification.getId());
        dlq.setUserId(notification.getUserId());
        dlq.setChannel(notification.getChannel());
        dlq.setPayload(notification.getPayload());
        dlq.setFailureReason(truncate(reason, 2000));
        dlq.setRetryCount(notification.getRetryCount());
        deadLetterRepository.save(dlq);
        log.info("Dead-letter record inserted for notificationId={}", notification.getId());
    }

    public Map<String, Object> parsePayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse payload JSON", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
