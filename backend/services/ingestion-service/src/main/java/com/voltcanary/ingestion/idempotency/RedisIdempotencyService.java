package com.voltcanary.ingestion.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.idempotency.redis-enabled", havingValue = "true")
class RedisIdempotencyService implements IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${app.idempotency.ttl:PT24H}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public boolean markIfNew(UUID deviceId, String sourceId, Instant timestamp) {
        Boolean inserted = redisTemplate.opsForValue()
                .setIfAbsent("telemetry:ingestion:" + deviceId + ":" + sourceId + ":" + timestamp, "1", ttl);
        return Boolean.TRUE.equals(inserted);
    }
}
