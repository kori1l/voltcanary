package com.voltcanary.analytics.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.outbox.reconciliation")
@Getter
@Setter
public class AnalyticsOutboxReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(1);
    private Duration lockLease = Duration.ofSeconds(30);
    private Duration failedAfter = Duration.ofMinutes(5);
    private Duration processingTimeout = Duration.ofMinutes(5);
    private Duration retryDelay = Duration.ofSeconds(30);
    private int batchSize = 100;
}
