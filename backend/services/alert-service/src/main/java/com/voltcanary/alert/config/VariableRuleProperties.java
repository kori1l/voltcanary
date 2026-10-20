package com.voltcanary.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.variables")
public class VariableRuleProperties {
    private String telemetryServiceBaseUrl = "http://localhost:8084";
    public String getTelemetryServiceBaseUrl() { return telemetryServiceBaseUrl; }
    public void setTelemetryServiceBaseUrl(String value) { telemetryServiceBaseUrl = value; }
}
