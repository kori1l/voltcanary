package com.voltcanary.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.variables")
public class VariableProcessingProperties {
    private boolean enabled;
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDeviceServiceBaseUrl() { return deviceServiceBaseUrl; }
    public void setDeviceServiceBaseUrl(String value) { deviceServiceBaseUrl = value; }
    public String getInternalServiceToken() { return internalServiceToken; }
    public void setInternalServiceToken(String value) { internalServiceToken = value; }
}
