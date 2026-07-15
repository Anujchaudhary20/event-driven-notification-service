package com.notificationservice.shared.domain;

/**
 * State machine for notification lifecycle.
 * PENDING -> PROCESSING -> SENT
 *                    \  -> RETRYING -> (retry) -> PROCESSING
 *                    \              -> (max retries) -> FAILED
 *                    \-> FAILED (permanent error)
 */
public enum NotificationState {
    PENDING,
    PROCESSING,
    SENT,
    RETRYING,
    FAILED
}
