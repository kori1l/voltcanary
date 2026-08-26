package com.voltcanary.ingestion.abuse;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.source.ResolvedSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IngestionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IngestionRateLimiter.class);

    private final IngestionProperties properties;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public IngestionRateLimiter(
            IngestionProperties properties,
            Optional<StringRedisTemplate> redisTemplate,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplate.orElse(null);
        this.meterRegistry = meterRegistry;
    }

    public void check(IncomingTelemetryMessage message, ResolvedSource source) {
        if (!properties.isRateLimitEnabled()) {
            return;
        }
        check("source:" + source.externalId(), properties.getSourceRequestsPerWindow());
        check("device:" + source.deviceId(), properties.getDeviceRequestsPerWindow());
        checkAccount(source.accountId());
        if (message.clientIp() != null && !message.clientIp().isBlank()) {
            check("ip:" + message.clientIp(), properties.getIpRequestsPerWindow());
        }
    }

    private void checkAccount(UUID accountId) {
        if (accountId != null) {
            check("account:" + accountId, properties.getAccountRequestsPerWindow());
        }
    }

    private void check(String key, int limit) {
        long windowStartedAt = currentWindowStartedAt();
        if (properties.isRedisRateLimitEnabled() && redisTemplate != null) {
            Boolean allowed = allowRedis(key, windowStartedAt, limit);
            if (allowed != null) {
                if (!allowed) {
                    reject();
                }
                return;
            }
        }
        if (!allowInMemory(key, windowStartedAt, limit)) {
            reject();
        }
    }

    private Boolean allowRedis(String key, long windowStartedAt, int limit) {
        String redisKey = "ingestion:rate-limit:" + key + ":" + windowStartedAt;
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "allowed";
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, redisTtl());
            }
            boolean allowed = count == null || count <= limit;
            outcome = allowed ? "allowed" : "limited";
            return allowed;
        } catch (RuntimeException exception) {
            outcome = "error";
            meterRegistry.counter(
                    "voltcanary.ingestion.redis.rate_limit.errors",
                    "reason",
                    exception.getClass().getSimpleName()).increment();
            log.warn("ingestionRateLimitRedisUnavailable reasonCode={}", exception.getClass().getSimpleName());
            return null;
        } finally {
            sample.stop(meterRegistry.timer("voltcanary.ingestion.redis.rate_limit.latency", "outcome", outcome));
        }
    }

    private boolean allowInMemory(String key, long windowStartedAt, int limit) {
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });
        return counter.count.incrementAndGet() <= limit;
    }

    private Duration redisTtl() {
        return properties.getRateLimitWindow().multipliedBy(2);
    }

    private void reject() {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Ingestion rate limit exceeded");
    }

    private long currentWindowStartedAt() {
        long windowMillis = Math.max(1, properties.getRateLimitWindow().toMillis());
        long now = System.currentTimeMillis();
        return now - (now % windowMillis);
    }

    private static final class WindowCounter {
        private final long windowStartedAt;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
