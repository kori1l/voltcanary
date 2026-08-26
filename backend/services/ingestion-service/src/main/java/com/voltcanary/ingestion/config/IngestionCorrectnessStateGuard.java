package com.voltcanary.ingestion.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class IngestionCorrectnessStateGuard implements SmartInitializingSingleton {

    private final IngestionProperties properties;
    private final Environment environment;

    IngestionCorrectnessStateGuard(IngestionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isMultiReplicaMode()) {
            return;
        }
        if (!idempotencyRedisEnabled()) {
            throw new IllegalStateException(
                    "Multi-replica ingestion requires app.idempotency.redis-enabled=true");
        }
        if (!properties.isRedisRateLimitEnabled()) {
            throw new IllegalStateException(
                    "Multi-replica ingestion requires app.ingestion.redis-rate-limit-enabled=true");
        }
        if (properties.isLocalCorrectnessStateEnabled()) {
            throw new IllegalStateException(
                    "app.ingestion.local-correctness-state-enabled is only valid when multi-replica mode is disabled");
        }
    }

    private boolean idempotencyRedisEnabled() {
        return environment.getProperty("app.idempotency.redis-enabled", Boolean.class, true);
    }
}
