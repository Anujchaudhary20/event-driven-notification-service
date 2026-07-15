package com.notificationservice.worker.service;

import com.notificationservice.shared.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MockNotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationProvider.class);

    public void send(NotificationChannel channel, Map<String, Object> payload) {
        log.info("MockNotificationProvider: sending via channel={}, payload={}", channel, payload);

        if (payload != null) {
            Object simulate = payload.get("simulate");
            if ("failTransient".equals(simulate)) {
                throw new TransientDeliveryException("Simulated transient failure");
            }
            if ("failPermanent".equals(simulate)) {
                throw new PermanentDeliveryException("Simulated permanent failure");
            }
        }

        log.info("MockNotificationProvider: successfully sent via channel={}", channel);
    }
}
