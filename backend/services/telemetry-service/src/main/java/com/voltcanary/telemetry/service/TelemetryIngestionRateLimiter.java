package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TelemetryIngestionRateLimiter {

    private final TelemetryValidationProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public TelemetryIngestionRateLimiter(TelemetryValidationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TelemetryIngestionRateLimiter(TelemetryValidationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean allow(UUID deviceId) {
        int limit = properties.getMaxEventsPerDevicePerMinute();
        if (limit <= 0 || deviceId == null) {
            return true;
        }
        long windowStartedAt = currentMinuteStartedAt();
        WindowCounter counter = counters.compute(deviceId.toString(), (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });
        return counter.count.incrementAndGet() <= limit;
    }

    private long currentMinuteStartedAt() {
        long now = clock.millis();
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
