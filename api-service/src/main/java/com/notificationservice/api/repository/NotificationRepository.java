package com.notificationservice.api.repository;

import com.notificationservice.shared.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);
}
