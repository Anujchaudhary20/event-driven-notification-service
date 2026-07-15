package com.notificationservice.worker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.notificationservice.shared.entity.DeadLetterNotification;

@Repository
public interface DeadLetterNotificationRepository extends JpaRepository<DeadLetterNotification, Long> {
}
