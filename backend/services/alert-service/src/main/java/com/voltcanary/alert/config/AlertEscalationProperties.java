package com.voltcanary.alert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert.escalation")
public class AlertEscalationProperties {

    private boolean enabled = true;
    private Duration warningAfter = Duration.ofMinutes(30);
    private Duration highAfter = Duration.ofMinutes(15);
    private Duration criticalAfter = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWarningAfter() {
        return warningAfter;
    }

    public void setWarningAfter(Duration warningAfter) {
        this.warningAfter = positiveOrDefault(warningAfter, Duration.ofMinutes(30));
    }

    public Duration getHighAfter() {
        return highAfter;
    }

    public void setHighAfter(Duration highAfter) {
        this.highAfter = positiveOrDefault(highAfter, Duration.ofMinutes(15));
    }

    public Duration getCriticalAfter() {
        return criticalAfter;
    }

    public void setCriticalAfter(Duration criticalAfter) {
        this.criticalAfter = positiveOrDefault(criticalAfter, Duration.ofMinutes(5));
    }

    private Duration positiveOrDefault(Duration value, Duration defaultValue) {
        if (value == null || value.isNegative() || value.isZero()) {
            return defaultValue;
        }
        return value;
    }
}
