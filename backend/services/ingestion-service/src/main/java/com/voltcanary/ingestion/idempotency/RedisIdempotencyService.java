package com.voltcanary.ingestion.idempotency;

import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.idempotency.redis-enabled", havingValue = "true")
class RedisIdempotencyService implements IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final MeterRegistry meterRegistry;

    RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            IngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.getIdempotencyTtl();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean markIfNew(UUID deviceId, String sourceId, Instant timestamp, Map<String, Object> metrics) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "duplicate";
        try {
            Boolean inserted = redisTemplate.opsForValue()
                    .setIfAbsent(key(deviceId, sourceId, timestamp, metrics), "1", ttl);
            boolean created = Boolean.TRUE.equals(inserted);
            outcome = created ? "created" : "duplicate";
            return created;
        } catch (RuntimeException exception) {
            outcome = "error";
            meterRegistry.counter(
                    "voltcanary.ingestion.redis.idempotency.errors",
                    "reason",
                    exception.getClass().getSimpleName()).increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("voltcanary.ingestion.redis.idempotency.latency", "outcome", outcome));
        }
    }

    @Override
    public void forget(UUID deviceId, String sourceId, Instant timestamp, Map<String, Object> metrics) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "deleted";
        try {
            redisTemplate.delete(key(deviceId, sourceId, timestamp, metrics));
        } catch (RuntimeException exception) {
            outcome = "error";
            meterRegistry.counter(
                    "voltcanary.ingestion.redis.idempotency.errors",
                    "reason",
                    exception.getClass().getSimpleName()).increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("voltcanary.ingestion.redis.idempotency.latency", "outcome", outcome));
        }
    }

    private String key(UUID deviceId, String sourceId, Instant timestamp, Map<String, Object> metrics) {
        return "telemetry:ingestion:" + deviceId + ":" + sourceId + ":" + timestamp + ":" + metrics.hashCode();
    }
}
