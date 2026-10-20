package com.voltcanary.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.simulator.target")
public class SimulationTargetProperties {
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
    public String getDeviceServiceBaseUrl() { return deviceServiceBaseUrl; }
    public void setDeviceServiceBaseUrl(String value) { deviceServiceBaseUrl = value; }
    public String getInternalServiceToken() { return internalServiceToken; }
    public void setInternalServiceToken(String value) { internalServiceToken = value; }
}
