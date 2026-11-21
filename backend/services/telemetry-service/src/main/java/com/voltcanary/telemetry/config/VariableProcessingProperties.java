package com.voltcanary.telemetry.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.variables")
@Getter
@Setter
public class VariableProcessingProperties {
    private boolean enabled;
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";

}
