package com.voltcanary.ingestion;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.device.DeviceValidationClient;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=true",
                "app.idempotency.redis-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class IngestionReadingsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private DeviceValidationClient deviceValidationClient;

    @MockitoBean
    private TelemetryEventPublisher telemetryEventPublisher;

    @Test
    void acceptsValidReadingAndPublishesKafkaEvent() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = Instant.parse("2026-05-12T12:30:00Z");
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(true);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, timestamp),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<TelemetryReceivedEvent> eventCaptor = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().deviceId()).isEqualTo(deviceId);
        assertThat(eventCaptor.getValue().sourceId()).isEqualTo(sourceId);
        assertThat(eventCaptor.getValue().metrics()).containsKey("activePowerW");
    }

    @Test
    void rejectsUnknownDeviceDataSource() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "missing-source";
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(false);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, Instant.parse("2026-05-12T12:31:00Z")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void duplicateReadingIsAcceptedButNotPublishedAgain() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = Instant.parse("2026-05-12T12:32:00Z");
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(true);

        restTemplate.postForEntity("/ingestion/readings", request(deviceId, sourceId, timestamp), String.class);
        ResponseEntity<String> duplicate = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, timestamp),
                String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(telemetryEventPublisher, times(1)).publish(any());
        verify(deviceValidationClient, times(2)).dataSourceExists(eq(deviceId), eq(sourceId));
    }

    private Map<String, Object> request(UUID deviceId, String sourceId, Instant timestamp) {
        return Map.of(
                "deviceId", deviceId,
                "sourceId", sourceId,
                "timestamp", timestamp.toString(),
                "metrics", Map.of(
                        "activePowerW", 840.2,
                        "voltageV", 230.1,
                        "currentA", 3.65,
                        "energyWh", 12450.7,
                        "rssi", -61,
                        "relayState", "ON"));
    }
}
