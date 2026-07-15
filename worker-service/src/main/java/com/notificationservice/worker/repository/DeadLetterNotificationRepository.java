package com.notificationservice.worker.repository;

import com.notificationservice.shared.entity.DeadLetterNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeadLetterNotificationRepository extends JpaRepository<DeadLetterNotification, Long> {
}
