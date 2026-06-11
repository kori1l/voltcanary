package com.voltcanary.analytics.service;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryValidatedEventHandler {

    private static final String SOURCE_TOPIC = "telemetry.validated.v1";

    private final ProcessedEventService processedEventService;
    private final AnalyticsJobService jobService;

    public TelemetryValidatedEventHandler(
            ProcessedEventService processedEventService,
            AnalyticsJobService jobService) {
        this.processedEventService = processedEventService;
        this.jobService = jobService;
    }

    @Transactional
    public boolean handle(TelemetryValidatedEvent event) {
        if (!processedEventService.markIfNew(SOURCE_TOPIC, event.eventId())) {
            return false;
        }
        jobService.enqueueRecalculation(event.deviceId(), event.timestamp());
        return true;
    }
}
