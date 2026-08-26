package com.voltcanary.ingestion.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionCorrectnessStateGuardTest {

    @Test
    void multiReplicaModeRequiresRedisIdempotency() {
        IngestionProperties properties = new IngestionProperties();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.idempotency.redis-enabled", "false");

        IngestionCorrectnessStateGuard guard = new IngestionCorrectnessStateGuard(properties, environment);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.idempotency.redis-enabled=true");
    }

    @Test
    void multiReplicaModeRequiresRedisRateLimiting() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedisRateLimitEnabled(false);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.idempotency.redis-enabled", "true");

        IngestionCorrectnessStateGuard guard = new IngestionCorrectnessStateGuard(properties, environment);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ingestion.redis-rate-limit-enabled=true");
    }

    @Test
    void localCorrectnessStateCannotBeEnabledForMultiReplicaMode() {
        IngestionProperties properties = new IngestionProperties();
        properties.setLocalCorrectnessStateEnabled(true);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.idempotency.redis-enabled", "true");

        IngestionCorrectnessStateGuard guard = new IngestionCorrectnessStateGuard(properties, environment);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only valid when multi-replica mode is disabled");
    }
}
