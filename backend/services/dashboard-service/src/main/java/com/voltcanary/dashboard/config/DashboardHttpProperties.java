package com.voltcanary.dashboard.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.dashboard")
public record DashboardHttpProperties(URI identityBaseUrl, URI deviceBaseUrl, Duration ownershipTimeout) {
}
