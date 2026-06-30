package com.voltcanary.analytics.service;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryValidatedEventHandler {

    private static final String SOURCE_TOPIC = "telemetry.validated.v1";

    private final ProcessedEventService processedEventService;
    private final AnalyticsJobService jobService;
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;

    public TelemetryValidatedEventHandler(
            ProcessedEventService processedEventService,
            AnalyticsJobService jobService,
            MaintenanceWindowProjectionService maintenanceWindowProjectionService) {
        this.processedEventService = processedEventService;
        this.jobService = jobService;
        this.maintenanceWindowProjectionService = maintenanceWindowProjectionService;
    }

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
