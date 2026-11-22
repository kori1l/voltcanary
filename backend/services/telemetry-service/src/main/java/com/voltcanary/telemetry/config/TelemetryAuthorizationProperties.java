package com.voltcanary.telemetry.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.authorization")
@Getter
@Setter
public class TelemetryAuthorizationProperties {

    private boolean enabled = true;
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
    private Duration timeout = Duration.ofSeconds(2);
}
