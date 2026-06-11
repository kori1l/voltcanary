package com.voltcanary.analytics.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.producer.AnalyticsDeadLetterPublisher;
import com.voltcanary.analytics.service.TelemetryValidatedEventHandler;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class TelemetryValidatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatedConsumer.class);
    private static final String SOURCE_TOPIC = "telemetry.validated.v1";

    private final ObjectMapper objectMapper;
    private final TelemetryValidatedEventHandler eventHandler;
    private final AnalyticsDeadLetterPublisher deadLetterPublisher;

    public TelemetryValidatedConsumer(
            ObjectMapper objectMapper,
            TelemetryValidatedEventHandler eventHandler,
            AnalyticsDeadLetterPublisher deadLetterPublisher) {
        this.objectMapper = objectMapper;
        this.eventHandler = eventHandler;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(topics = "telemetry.validated.v1", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String eventPayload) {
        TelemetryValidatedEvent event = readEvent(eventPayload);
        if (event.deviceId() == null) {
            deadLetterPublisher.publish(SOURCE_TOPIC, eventPayload, "TelemetryValidatedEvent deviceId is required");
            throw new IllegalArgumentException("TelemetryValidatedEvent deviceId is required");
        }
        boolean processed = eventHandler.handle(event);
        log.info(
                "analyticsTelemetryValidatedReceived eventId={} deviceId={} timestamp={} processed={}",
                event.eventId(),
                event.deviceId(),
                event.timestamp(),
                processed);
    }

    private TelemetryValidatedEvent readEvent(String eventPayload) {
        try {
            return objectMapper.readValue(eventPayload, TelemetryValidatedEvent.class);
        } catch (JsonProcessingException exception) {
            deadLetterPublisher.publish(SOURCE_TOPIC, eventPayload, "Invalid TelemetryValidatedEvent payload");
            throw new IllegalArgumentException("Invalid TelemetryValidatedEvent payload", exception);
        }
    }
}
