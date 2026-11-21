package com.voltcanary.telemetry.producer;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.event.TelemetryDeadLetterEvent;
import com.voltcanary.telemetry.event.TelemetryTopics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelemetryDeadLetterPublisher {

    private final TelemetryOutboxService outboxService;

    public void publish(TelemetryReceivedEvent event, String reason, List<String> qualityFlags) {
        TelemetryDeadLetterEvent deadLetterEvent = new TelemetryDeadLetterEvent(
                UUID.randomUUID(),
                reason,
                qualityFlags,
                event,
                Instant.now());
        String key = event == null || event.deviceId() == null ? deadLetterEvent.deadLetterId().toString() : event.deviceId().toString();
        log.warn(
                "Publishing telemetry DLQ topic={} deadLetterId={} reason={}",
                TelemetryTopics.TELEMETRY_RAW_DLQ_V1,
                deadLetterEvent.deadLetterId(),
                reason);
        outboxService.enqueue(
                "telemetry-dlq:" + deadLetterEvent.deadLetterId(),
                TelemetryTopics.TELEMETRY_RAW_DLQ_V1,
                key,
                deadLetterEvent);
    }
}
