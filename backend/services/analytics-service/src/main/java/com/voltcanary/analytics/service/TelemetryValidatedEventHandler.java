package com.voltcanary.analytics.service;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TelemetryValidatedEventHandler {

    private static final String SOURCE_TOPIC = "telemetry.validated.v1";

    private final ProcessedEventService processedEventService;
    private final AnalyticsJobService jobService;
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;

    @Transactional
    public boolean handle(TelemetryValidatedEvent event) {
        if (!processedEventService.markIfNew(SOURCE_TOPIC, event.eventId())) {
            return false;
        }
        if (maintenanceWindowProjectionService.excludesAnalyticsAt(event.deviceId(), event.timestamp())) {
            return true;
        }
        jobService.enqueueRecalculation(event.deviceId(), event.timestamp());
        return true;
    }
}
