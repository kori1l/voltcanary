package com.voltcanary.device.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.native-data.dependencies")
public record NativeDependencyProperties(
    @DefaultValue("http://localhost:8085") URI analytics,
    @DefaultValue("http://localhost:8086") URI alerts,
    @DefaultValue("http://localhost:8089") URI dashboards,
    @DefaultValue("2s") Duration timeout
) {}
