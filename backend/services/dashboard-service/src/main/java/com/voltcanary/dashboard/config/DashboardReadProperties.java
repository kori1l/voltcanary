package com.voltcanary.dashboard.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.dashboard.read")
public record DashboardReadProperties(URI deviceBaseUrl, URI telemetryBaseUrl, URI analyticsBaseUrl,
        URI alertBaseUrl, String internalServiceToken, Duration timeout) {
}
