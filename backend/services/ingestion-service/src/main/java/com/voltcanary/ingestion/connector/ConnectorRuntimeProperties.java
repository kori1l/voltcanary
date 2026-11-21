package com.voltcanary.ingestion.connector;

import java.time.Duration;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ingestion.connector-runtime")
@Getter
@Setter
public class ConnectorRuntimeProperties {

    private boolean enabled = true;
    private String instanceId = "ingestion-" + UUID.randomUUID();
    private Duration tickInterval = Duration.ofSeconds(5);
    private Duration defaultPollingInterval = Duration.ofMinutes(1);
    private Duration leaseTtl = Duration.ofSeconds(30);
    private Duration initialBackoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofMinutes(1);
    private double jitterRatio = 0.2;

}
