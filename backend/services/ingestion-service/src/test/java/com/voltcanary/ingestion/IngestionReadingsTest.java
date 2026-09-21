package com.voltcanary.ingestion;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=true",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.redis-rate-limit-enabled=false",
                "app.ingestion.multi-replica-mode=false",
                "app.ingestion.local-correctness-state-enabled=true",
                "app.ingestion.backpressure-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class IngestionReadingsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryEventPublisher telemetryEventPublisher;

    @Autowired
    private SourceRegistryCache sourceRegistryCache;

    @Test
    void acceptsValidReadingAndPublishesKafkaEvent() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(30);
        seedSource(deviceId, sourceId, "ACTIVE");

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

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, recentTimestamp(31)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void duplicateReadingIsAcceptedButNotPublishedAgain() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(32);
        seedSource(deviceId, sourceId, "ACTIVE");

        restTemplate.postForEntity("/ingestion/readings", request(deviceId, sourceId, timestamp), String.class);
        ResponseEntity<String> duplicate = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, timestamp),
                String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(duplicate.getBody()).contains("\"duplicateCount\":1");
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    @Test
    void rejectsUnsupportedMetricName() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        seedSource(deviceId, sourceId, "ACTIVE");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings",
                Map.of(
                        "deviceId", deviceId,
                        "sourceId", sourceId,
                        "timestamp", recentTimestamp(33).toString(),
                        "metrics", Map.of("unsupportedMetric", 24.5)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void batchReturnsPartialSuccessWithPerItemStatus() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        seedSource(deviceId, sourceId, "ACTIVE");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings/batch",
                Map.of("readings", java.util.List.of(
                        request(deviceId, sourceId, recentTimestamp(34)),
                        Map.of(
                                "deviceId", deviceId,
                                "sourceId", sourceId,
                                "timestamp", recentTimestamp(35).toString(),
                                "metrics", Map.of("unsupportedMetric", 1)))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"status\":\"partial\"");
        assertThat(response.getBody()).contains("\"acceptedCount\":1");
        assertThat(response.getBody()).contains("\"rejectedCount\":1");
        assertThat(response.getBody()).contains("\"index\":1");
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    @Test
    void publishFailureReturnsUnavailableAndDoesNotConsumeIdempotencyKey() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(36);
        seedSource(deviceId, sourceId, "ACTIVE");
        doThrow(new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Telemetry broker publish failed"))
                .doNothing()
                .when(telemetryEventPublisher).publish(any());

        ResponseEntity<String> failed = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, timestamp),
                String.class);
        ResponseEntity<String> retried = restTemplate.postForEntity(
                "/ingestion/readings",
                request(deviceId, sourceId, timestamp),
                String.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(telemetryEventPublisher, times(2)).publish(any());
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

    private Instant recentTimestamp(long secondsAgo) {
        return Instant.now().minusSeconds(secondsAgo);
    }

    private void seedSource(UUID deviceId, String sourceId, String status) {
        sourceRegistryCache.put(new InternalDataSourceResponse(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                sourceId,
                "GENERIC_HTTP_PUSH",
                "REST",
                status,
                null,
                null), System.nanoTime());
    }
}
