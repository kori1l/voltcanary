package com.voltcanary.ingestion.connector;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class ConnectorBackoffPolicy {

    private final ConnectorRuntimeProperties properties;

    public ConnectorBackoffPolicy(ConnectorRuntimeProperties properties) {
        this.properties = properties;
    }

    public Duration nextDelay(int consecutiveFailures) {
        int exponent = Math.max(0, consecutiveFailures - 1);
        long baseMillis = properties.getInitialBackoff().toMillis();
        long maxMillis = properties.getMaxBackoff().toMillis();
        long exponentialMillis = baseMillis;
        for (int i = 0; i < exponent && exponentialMillis < maxMillis; i++) {
            exponentialMillis = Math.min(maxMillis, exponentialMillis * 2);
        }
        double jitterRatio = Math.max(0.0, properties.getJitterRatio());
        long jitterBound = Math.round(exponentialMillis * jitterRatio);
        long jitter = jitterBound == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitterBound, jitterBound + 1);
        return Duration.ofMillis(Math.max(0, Math.min(maxMillis, exponentialMillis + jitter)));
    }
}
