package com.voltcanary.ingestion.source;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SourceUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SourceUsageRecorder.class);
    private static final Duration UPDATE_INTERVAL = Duration.ofMinutes(1);

    private final DeviceServiceSourceClient sourceClient;
    private final Map<UUID, Instant> lastUpdateBySource = new ConcurrentHashMap<>();

    public SourceUsageRecorder(DeviceServiceSourceClient sourceClient) {
        this.sourceClient = sourceClient;
    }

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
        try {
            sourceClient.markTokenUsed(source.dataSourceId());
        } catch (RuntimeException exception) {
            log.warn("Failed to update data source token usage dataSourceId={}", source.dataSourceId(), exception);
        }
    }
}
