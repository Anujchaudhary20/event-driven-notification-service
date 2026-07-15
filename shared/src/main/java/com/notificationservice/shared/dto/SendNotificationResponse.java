package com.notificationservice.shared.dto;

public class SendNotificationResponse {

    private Long notificationId;
    private String state;

    public SendNotificationResponse(Long notificationId, String state) {
        this.notificationId = notificationId;
        this.state = state;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
