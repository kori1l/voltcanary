package com.voltcanary.telemetry.producer;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.event.TelemetryTopics;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelemetryValidatedPublisher {

    private final TelemetryOutboxService outboxService;

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
