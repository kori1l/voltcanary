package com.voltcanary.analytics.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.authorization")
@Getter
@Setter
public class AnalyticsAuthorizationProperties {

    private boolean enabled = true;
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private Duration timeout = Duration.ofSeconds(2);
}
