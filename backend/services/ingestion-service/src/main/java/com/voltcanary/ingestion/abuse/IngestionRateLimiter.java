package com.voltcanary.ingestion.abuse;

import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
import com.voltcanary.ingestion.source.ResolvedSource;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IngestionRateLimiter {

    private final IngestionProperties properties;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public IngestionRateLimiter(IngestionProperties properties) {
        this.properties = properties;
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
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });
        if (counter.count.incrementAndGet() > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Ingestion rate limit exceeded");
        }
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
