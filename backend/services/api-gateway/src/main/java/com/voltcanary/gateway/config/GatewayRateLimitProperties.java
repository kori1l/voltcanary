package com.voltcanary.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.rate-limit")
public class GatewayRateLimitProperties {

    private boolean enabled = true;
    private Duration window = Duration.ofMinutes(1);
    private int authRequestsPerWindow = 30;
    private int ingestionRequestsPerWindow = 120;
    private int generalRequestsPerWindow = 600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getAuthRequestsPerWindow() {
        return authRequestsPerWindow;
    }

    public void setAuthRequestsPerWindow(int authRequestsPerWindow) {
        this.authRequestsPerWindow = authRequestsPerWindow;
    }

    public int getIngestionRequestsPerWindow() {
        return ingestionRequestsPerWindow;
    }

    public void setIngestionRequestsPerWindow(int ingestionRequestsPerWindow) {
        this.ingestionRequestsPerWindow = ingestionRequestsPerWindow;
    }

    public int getGeneralRequestsPerWindow() {
        return generalRequestsPerWindow;
    }

    public void setGeneralRequestsPerWindow(int generalRequestsPerWindow) {
        this.generalRequestsPerWindow = generalRequestsPerWindow;
    }
}
