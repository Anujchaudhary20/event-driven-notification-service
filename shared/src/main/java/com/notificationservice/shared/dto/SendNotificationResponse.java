package com.notificationservice.shared.dto;

import com.notificationservice.shared.domain.NotificationState;

public class SendNotificationResponse {

    private Long notificationId;
    private NotificationState state;

    public SendNotificationResponse() {
    }

    public SendNotificationResponse(Long notificationId, NotificationState state) {
        this.notificationId = notificationId;
        this.state = state;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public NotificationState getState() {
        return state;
    }

    public void setState(NotificationState state) {
        this.state = state;
    }
}
