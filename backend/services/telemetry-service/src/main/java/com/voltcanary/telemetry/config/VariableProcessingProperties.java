package com.voltcanary.telemetry.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.variables")
@Validated
@Getter
@Setter
public class VariableProcessingProperties {

    private boolean enabled;

    @Min(1)
    private int pendingEventCapacity = 10_000;

    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
}
