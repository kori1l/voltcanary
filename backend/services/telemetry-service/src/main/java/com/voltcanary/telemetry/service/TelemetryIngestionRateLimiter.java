package com.voltcanary.telemetry.service;

import com.voltcanary.common.security.BoundedWindowRateLimiter;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TelemetryIngestionRateLimiter {

    private static final Duration REDIS_KEY_TTL = Duration.ofMinutes(2);

    private static final RedisScript<Long> COUNT_SCRIPT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('PTTL',KEYS[1])<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n;", Long.class);

    private final TelemetryValidationProperties properties;
    private final BoundedWindowRateLimiter counters =
            new BoundedWindowRateLimiter(10_000);
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
            Long count = redisTemplate.execute(COUNT_SCRIPT, List.of(key), Long.toString(REDIS_KEY_TTL.toMillis()));
            return count != null && count <= limit;
        } catch (RuntimeException exception) {
            log.warn("telemetryRateLimitRedisUnavailable reasonCode={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private boolean allowInMemory(UUID deviceId, long windowStartedAt, int limit) {
        return counters.allow(deviceId.toString(), windowStartedAt, limit);
    }

    private long currentMinuteStartedAt() {
        long now = System.currentTimeMillis();
        return now - (now % 60_000);
    }

}
