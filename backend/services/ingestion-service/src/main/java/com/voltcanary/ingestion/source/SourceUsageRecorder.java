package com.voltcanary.ingestion.source;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SourceUsageRecorder {

    private static final Duration UPDATE_INTERVAL = Duration.ofMinutes(1);

    private final Map<UUID, Instant> lastUpdateBySource = new ConcurrentHashMap<>();

    public void record(ResolvedSource source) {
        if (source.dataSourceId() == null) {
            return;
        }
        Instant now = Instant.now();
        Instant previous = lastUpdateBySource.get(source.dataSourceId());
        if (previous != null && previous.plus(UPDATE_INTERVAL).isAfter(now)) {
            return;
        }
        lastUpdateBySource.put(source.dataSourceId(), now);
    }

    public Instant lastSeen(UUID dataSourceId) {
        return lastUpdateBySource.get(dataSourceId);
    }
}
