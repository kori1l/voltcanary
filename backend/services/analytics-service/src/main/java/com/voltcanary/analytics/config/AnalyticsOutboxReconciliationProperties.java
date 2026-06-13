package com.voltcanary.analytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.outbox.reconciliation")
public class AnalyticsOutboxReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(1);
    private Duration lockLease = Duration.ofSeconds(30);
    private Duration failedAfter = Duration.ofMinutes(5);
    private Duration processingTimeout = Duration.ofMinutes(5);
    private Duration retryDelay = Duration.ofSeconds(30);
    private int batchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getLockLease() {
        return lockLease;
    }

    public void setLockLease(Duration lockLease) {
        this.lockLease = lockLease;
    }

    public Duration getFailedAfter() {
        return failedAfter;
    }

    public void setFailedAfter(Duration failedAfter) {
        this.failedAfter = failedAfter;
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    public void setProcessingTimeout(Duration processingTimeout) {
        this.processingTimeout = processingTimeout;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
