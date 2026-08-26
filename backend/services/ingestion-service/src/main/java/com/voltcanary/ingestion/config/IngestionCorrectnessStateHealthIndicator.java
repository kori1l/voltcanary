package com.voltcanary.ingestion.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component("ingestionCorrectnessState")
class IngestionCorrectnessStateHealthIndicator implements HealthIndicator {

    private final IngestionProperties properties;
    private final Environment environment;

    IngestionCorrectnessStateHealthIndicator(IngestionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public Health health() {
        String idempotencyState = idempotencyRedisEnabled() ? "shared-redis" : "local-only";
        String rateLimitState = properties.isRedisRateLimitEnabled() ? "shared-redis" : "local-only";
        Health.Builder builder = properties.isMultiReplicaMode()
                ? Health.up()
                : Health.status("DEGRADED");
        return builder
                .withDetail("mode", properties.isMultiReplicaMode() ? "multi-replica" : "local-only")
                .withDetail("idempotency", idempotencyState)
                .withDetail("rateLimit", rateLimitState)
                .withDetail("localCorrectnessStateEnabled", properties.isLocalCorrectnessStateEnabled())
                .build();
    }

    private boolean idempotencyRedisEnabled() {
        return environment.getProperty("app.idempotency.redis-enabled", Boolean.class, true);
    }
}
