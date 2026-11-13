package com.voltcanary.device.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.source-capture")
public class SourceCaptureProperties {
    private String telemetryServiceBaseUrl = "http://localhost:8084";
    private String internalServiceToken = "";
    private Duration timeout = Duration.ofSeconds(2);
    public String getTelemetryServiceBaseUrl() { return telemetryServiceBaseUrl; }
    public void setTelemetryServiceBaseUrl(String value) { telemetryServiceBaseUrl = value; }
    public String getInternalServiceToken() { return internalServiceToken; }
    public void setInternalServiceToken(String value) { internalServiceToken = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
}
