package com.voltcanary.ingestion.kafka;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.event.TelemetryTopics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TelemetryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventPublisher.class);

    private final KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;
    private final IngestionProperties properties;
    private final MeterRegistry meterRegistry;

    public TelemetryEventPublisher(
            KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate,
            IngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void publish(TelemetryReceivedEvent event) {
        log.info(
                "Publishing telemetry event topic={} eventId={} deviceId={}",
                TelemetryTopics.TELEMETRY_RAW_V1,
                event.eventId(),
                event.deviceId());
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "published";
        try {
            kafkaTemplate.send(TelemetryTopics.TELEMETRY_RAW_V1, event.deviceId().toString(), event)
                    .get(properties.getKafkaAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            outcome = "failed";
            meterRegistry.counter("voltcanary.ingestion.kafka.publish", "outcome", outcome).increment();
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry broker publish interrupted", exception);
        } catch (Exception exception) {
            outcome = "failed";
            meterRegistry.counter("voltcanary.ingestion.kafka.publish", "outcome", outcome).increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry broker publish failed", exception);
        } finally {
            if ("published".equals(outcome)) {
                meterRegistry.counter("voltcanary.ingestion.kafka.publish", "outcome", outcome).increment();
            }
            sample.stop(Timer.builder("voltcanary.ingestion.kafka.publish.latency")
                    .description("Kafka telemetry publish acknowledgement latency")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
