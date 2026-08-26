package com.voltcanary.ingestion.kafka;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.event.TelemetryTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryEventPublisherTest {

    @Test
    void publishesTelemetryRawWithDeviceIdKey() {
        KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate = mock(KafkaTemplate.class);
        TelemetryEventPublisher publisher = new TelemetryEventPublisher(
                kafkaTemplate,
                new IngestionProperties(),
                new SimpleMeterRegistry());
        TelemetryReceivedEvent event = event();
        when(kafkaTemplate.send(TelemetryTopics.TELEMETRY_RAW_V1, event.deviceId().toString(), event))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(event);

        verify(kafkaTemplate).send(TelemetryTopics.TELEMETRY_RAW_V1, event.deviceId().toString(), event);
    }

    private TelemetryReceivedEvent event() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-06-13T08:00:00Z");
        return new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                timestamp,
                Map.of("activePowerW", 840.2),
                timestamp.plusMillis(100));
    }
}
