package com.notificationservice.api.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.notificationservice.shared.entity.Notification;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Idempotency lookup: if key exists, return existing notification (exactly-once guarantee).
     */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);
}
