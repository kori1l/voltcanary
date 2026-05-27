package com.voltcanary.telemetry.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.authorization")
public class TelemetryAuthorizationProperties {

    private boolean enabled = true;
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private Duration timeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDeviceServiceBaseUrl() {
        return deviceServiceBaseUrl;
    }

    public void setDeviceServiceBaseUrl(String deviceServiceBaseUrl) {
        this.deviceServiceBaseUrl = deviceServiceBaseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
