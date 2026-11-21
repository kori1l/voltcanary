package com.voltcanary.ingestion.config;

import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ingestion")
@Getter
@Setter
public class IngestionProperties {

    private Instant minAcceptedTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    private Duration maxFutureSkew = Duration.ofSeconds(30);
    private Duration maxBackfillAge = Duration.ofDays(30);
    private int maxMetricsPerReading = 100;
    private int maxReadingsPerBatch = 500;
    private int maxSourceIdLength = 255;
    private int maxMetricNameLength = 100;
    private Duration kafkaAckTimeout = Duration.ofSeconds(3);
    private Duration idempotencyTtl = Duration.ofHours(24);
    private boolean rateLimitEnabled = true;
    private boolean redisRateLimitEnabled = true;
    private boolean multiReplicaMode = true;
    private boolean localCorrectnessStateEnabled = false;
    private Duration rateLimitWindow = Duration.ofMinutes(1);
    private int sourceRequestsPerWindow = 600;
    private int deviceRequestsPerWindow = 1_200;
    private int accountRequestsPerWindow = 5_000;
    private int ipRequestsPerWindow = 1_000;
    private boolean backpressureEnabled = true;
    private Duration backpressureCooldown = Duration.ofSeconds(30);
    private int auditCapacity = 1_000;
    private boolean kafkaPublishEnabled = false;
    private Mqtt mqtt = new Mqtt();

    public static class Mqtt {
        @Getter
        @Setter
        private boolean enabled = false;
        @Getter
        @Setter
        private String brokerUrl = "tcp://localhost:1883";
        @Getter
        @Setter
        private String clientId = "voltcanary-ingestion";
        @Getter
        @Setter
        private String topicFilter = "voltcanary/sources/+/telemetry";

    }
}
