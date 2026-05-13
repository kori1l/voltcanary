package com.voltcanary.ingestion.kafka;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.event.TelemetryTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventPublisher.class);

    private final KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;

    public TelemetryEventPublisher(KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(TelemetryReceivedEvent event) {
        log.info(
                "Publishing telemetry event topic={} eventId={} deviceId={}",
                TelemetryTopics.TELEMETRY_RAW_V1,
                event.eventId(),
                event.deviceId());
        kafkaTemplate.send(TelemetryTopics.TELEMETRY_RAW_V1, event.deviceId().toString(), event);
    }
}
