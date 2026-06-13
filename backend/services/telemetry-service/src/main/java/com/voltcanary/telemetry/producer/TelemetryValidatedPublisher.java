package com.voltcanary.telemetry.producer;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.event.TelemetryTopics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TelemetryValidatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatedPublisher.class);

    private final TelemetryOutboxService outboxService;

    public TelemetryValidatedPublisher(TelemetryOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    public void publish(TelemetryValidatedEvent event) {
        publish(event, null);
    }

    public void publish(TelemetryValidatedEvent event, UUID readingId) {
        log.info(
                "Publishing validated telemetry topic={} eventId={} deviceId={}",
                TelemetryTopics.TELEMETRY_VALIDATED_V1,
                event.eventId(),
                event.deviceId());
        outboxService.enqueue(
                readingId == null ? "telemetry-validated:" + event.eventId() : "telemetry-validated-reading:" + readingId,
                TelemetryTopics.TELEMETRY_VALIDATED_V1,
                event.deviceId().toString(),
                event,
                readingId);
    }
}
