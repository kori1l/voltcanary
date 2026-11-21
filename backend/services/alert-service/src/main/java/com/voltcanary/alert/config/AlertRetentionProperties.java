package com.voltcanary.alert.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert.retention")
@Getter
public class AlertRetentionProperties {

    @Setter
    private boolean enabled = true;
    private long initialDelayMs = 300000;
    private long scanIntervalMs = 3600000;
    private Duration resolvedAlertTtl = Duration.ofDays(180);
    private Duration eventTtl = Duration.ofDays(365);
    private Duration notificationAttemptTtl = Duration.ofDays(90);

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = Math.max(0, initialDelayMs);
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = Math.max(60000, scanIntervalMs);
    }

    public void setResolvedAlertTtl(Duration resolvedAlertTtl) {
        this.resolvedAlertTtl = resolvedAlertTtl == null ? Duration.ZERO : resolvedAlertTtl;
    }

    public void setEventTtl(Duration eventTtl) {
        this.eventTtl = eventTtl == null ? Duration.ZERO : eventTtl;
    }

    public void setNotificationAttemptTtl(Duration notificationAttemptTtl) {
        this.notificationAttemptTtl = notificationAttemptTtl == null ? Duration.ZERO : notificationAttemptTtl;
    }
}
