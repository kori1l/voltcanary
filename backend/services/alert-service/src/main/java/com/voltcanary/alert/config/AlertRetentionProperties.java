package com.voltcanary.alert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert.retention")
public class AlertRetentionProperties {

    private boolean enabled = true;
    private long initialDelayMs = 300000;
    private long scanIntervalMs = 3600000;
    private Duration resolvedAlertTtl = Duration.ofDays(180);
    private Duration eventTtl = Duration.ofDays(365);
    private Duration notificationAttemptTtl = Duration.ofDays(90);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = Math.max(0, initialDelayMs);
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = Math.max(60000, scanIntervalMs);
    }

    public Duration getResolvedAlertTtl() {
        return resolvedAlertTtl;
    }

    public void setResolvedAlertTtl(Duration resolvedAlertTtl) {
        this.resolvedAlertTtl = resolvedAlertTtl == null ? Duration.ZERO : resolvedAlertTtl;
    }

    public Duration getEventTtl() {
        return eventTtl;
    }

    public void setEventTtl(Duration eventTtl) {
        this.eventTtl = eventTtl == null ? Duration.ZERO : eventTtl;
    }

    public Duration getNotificationAttemptTtl() {
        return notificationAttemptTtl;
    }

    public void setNotificationAttemptTtl(Duration notificationAttemptTtl) {
        this.notificationAttemptTtl = notificationAttemptTtl == null ? Duration.ZERO : notificationAttemptTtl;
    }
}
