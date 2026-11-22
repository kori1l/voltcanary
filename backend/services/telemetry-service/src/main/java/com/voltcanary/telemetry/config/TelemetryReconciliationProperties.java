package com.voltcanary.telemetry.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.reconciliation")
@Getter
@Setter
public class TelemetryReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(1);
    private Duration readingGracePeriod = Duration.ofSeconds(30);
    private int batchSize = 100;
}
