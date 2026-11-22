package com.voltcanary.ingestion.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ingestion.source-registry-reconciliation")
@Getter
@Setter
public class SourceRegistryReconciliationProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(1);
    private int batchSize = 100;
}
