package com.voltcanary.ingestion.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class IngestionCorrectnessStateMetrics {

    IngestionCorrectnessStateMetrics(
            IngestionProperties properties,
            Environment environment,
            MeterRegistry meterRegistry) {
        String mode = properties.isMultiReplicaMode() ? "multi-replica" : "local-only";
        String idempotencyState = environment.getProperty("app.idempotency.redis-enabled", Boolean.class, true)
                ? "shared-redis"
                : "local-only";
        String rateLimitState = properties.isRedisRateLimitEnabled() ? "shared-redis" : "local-only";
        Gauge.builder("voltcanary.ingestion.correctness.state", () -> 1)
                .description("Configured ingestion correctness-state mode")
                .tag("mode", mode)
                .tag("idempotency", idempotencyState)
                .tag("rate_limit", rateLimitState)
                .register(meterRegistry);
    }
}
