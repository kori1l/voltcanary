package com.voltcanary.telemetry.producer;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.event.TelemetryTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryValidatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatedPublisher.class);

    private final KafkaTemplate<String, TelemetryValidatedEvent> kafkaTemplate;

    public TelemetryValidatedPublisher(KafkaTemplate<String, TelemetryValidatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(TelemetryValidatedEvent event) {
        log.info(
                "Publishing validated telemetry topic={} eventId={} deviceId={}",
                TelemetryTopics.TELEMETRY_VALIDATED_V1,
                event.eventId(),
                event.deviceId());
        kafkaTemplate.send(TelemetryTopics.TELEMETRY_VALIDATED_V1, event.deviceId().toString(), event);
    }
}
