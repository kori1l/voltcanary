package com.voltcanary.aiagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.conversations")
public class ConversationProperties {
    private Duration retention = Duration.ofDays(30);
    private Duration lease = Duration.ofMinutes(10);

    public Duration getRetention() { return retention; }
    public void setRetention(Duration value) {
        if (value == null || value.compareTo(Duration.ofDays(1)) < 0 || value.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Conversation retention must be between 1 and 365 days");
        }
        retention = value;
    }
    public Duration getLease() { return lease; }
}
