package com.voltcanary.telemetry.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.reconciliation")
public class TelemetryReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(1);
    private Duration readingGracePeriod = Duration.ofSeconds(30);
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

    public Duration getReadingGracePeriod() {
        return readingGracePeriod;
    }

    public void setReadingGracePeriod(Duration readingGracePeriod) {
        this.readingGracePeriod = readingGracePeriod;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
