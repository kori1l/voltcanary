package com.voltcanary.aiagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.tasks")
public class AiTaskProperties {
    private Duration expiry = Duration.ofHours(24);
    private Duration retention = Duration.ofDays(30);

    public Duration getExpiry() { return expiry; }
    public void setExpiry(Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative() || expiry.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("AI task retry window must be positive and at most 24 hours");
        }
        this.expiry = expiry;
    }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
}
