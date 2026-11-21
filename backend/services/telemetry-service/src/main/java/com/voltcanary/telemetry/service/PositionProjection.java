package com.voltcanary.telemetry.service;

import com.voltcanary.common.events.telemetry.GpsMetrics;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.repository.PositionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PositionProjection {
    private final PositionRepository repository;

    public boolean valid(TelemetryReceivedEvent event) {
        return GpsMetrics.valid(event.metrics());
    }

    public void accepted(TelemetryReceivedEvent event, UUID readingId, List<String> quality) {
        if (GpsMetrics.hasPosition(event.metrics()) && valid(event)
                && !quality.contains("INVALID_VALUE") && !quality.contains("OUT_OF_RANGE")
                && !quality.contains("DUPLICATE")) {
            repository.insert(event, readingId, quality);
        }
    }
}
