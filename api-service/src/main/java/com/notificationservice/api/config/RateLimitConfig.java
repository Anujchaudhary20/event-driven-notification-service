package com.notificationservice.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Value("${notification.rate-limit.per-user-per-minute:10}")
    private int perUserPerMinute;

    public int getPerUserPerMinute() {
        return perUserPerMinute;
    }

    public void setPerUserPerMinute(int perUserPerMinute) {
        this.perUserPerMinute = perUserPerMinute;
    }
}
