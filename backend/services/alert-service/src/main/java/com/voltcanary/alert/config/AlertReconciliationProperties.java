package com.voltcanary.alert.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.alert.reconciliation")
@Getter
@Setter
public class AlertReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofSeconds(30);
    private Duration lockLease = Duration.ofSeconds(20);
    private Duration processingTimeout = Duration.ofMinutes(5);
    private Duration retryDelay = Duration.ofSeconds(30);
    private int batchSize = 25;

}
