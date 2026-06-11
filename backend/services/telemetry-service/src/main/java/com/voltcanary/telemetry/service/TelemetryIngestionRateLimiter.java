package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryIngestionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionRateLimiter.class);
    private static final Duration REDIS_KEY_TTL = Duration.ofMinutes(2);

    private final TelemetryValidationProperties properties;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public TelemetryIngestionRateLimiter(
            TelemetryValidationProperties properties,
            Optional<StringRedisTemplate> redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate.orElse(null);
    }

    public boolean allow(UUID deviceId) {
        int limit = properties.getMaxEventsPerDevicePerMinute();
        if (limit <= 0 || deviceId == null) {
            return true;
        }
        long windowStartedAt = currentMinuteStartedAt();
        if (properties.isRedisRateLimitEnabled() && redisTemplate != null) {
            Boolean allowed = allowRedis(deviceId, windowStartedAt, limit);
            if (allowed != null) {
                return allowed;
            }
        }
        return allowInMemory(deviceId, windowStartedAt, limit);
    }

    private Boolean allowRedis(UUID deviceId, long windowStartedAt, int limit) {
        String key = "telemetry:rate-limit:device:" + deviceId + ":" + windowStartedAt;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, REDIS_KEY_TTL);
            }
            return count == null || count <= limit;
        } catch (RuntimeException exception) {
            log.warn("telemetryRateLimitRedisUnavailable reasonCode={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private boolean allowInMemory(UUID deviceId, long windowStartedAt, int limit) {
        WindowCounter counter = counters.compute(deviceId.toString(), (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });
        return counter.count.incrementAndGet() <= limit;
    }

    private long currentMinuteStartedAt() {
        long now = System.currentTimeMillis();
        return now - (now % 60_000);
    }

    private static final class WindowCounter {
        private final long windowStartedAt;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
