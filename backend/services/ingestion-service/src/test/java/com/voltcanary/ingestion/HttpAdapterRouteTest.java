package com.voltcanary.ingestion;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.pipeline.IngestionResult;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=false",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.redis-rate-limit-enabled=false",
                "app.ingestion.multi-replica-mode=false",
                "app.ingestion.local-correctness-state-enabled=true",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class HttpAdapterRouteTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryIngestionPipeline pipeline;

    @Test
    void singleReadingReachesPipelineAsCanonicalHttpAdapterMessage() {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now().minusSeconds(30);
        when(pipeline.ingest(any()))
                .thenReturn(new IngestionResult(true, eventId, "accepted"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/ingestion/readings",
                HttpMethod.POST,
                new HttpEntity<>(request(deviceId, "plug-001", timestamp), headers()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains(eventId.toString());
        IncomingTelemetryMessage message = capturedMessages(1).getFirst();
        assertCanonicalHttpMessage(message, deviceId, "plug-001", timestamp);
        assertThat(message.historicalImport()).isFalse();
    }

    @Test
    void importReadingSetsCanonicalImportMode() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now().minusSeconds(7_200);
        when(pipeline.ingest(any()))
                .thenReturn(new IngestionResult(true, UUID.randomUUID(), "accepted"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/ingestion/imports/readings",
                HttpMethod.POST,
                new HttpEntity<>(request(deviceId, "plug-001", timestamp), headers()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        IncomingTelemetryMessage message = capturedMessages(1).getFirst();
        assertCanonicalHttpMessage(message, deviceId, "plug-001", timestamp);
        assertThat(message.historicalImport()).isTrue();
    }

    @Test
    void batchRoutesEachItemThroughCanonicalHttpAdapterMessages() {
        UUID deviceId = UUID.randomUUID();
        Instant firstTimestamp = Instant.now().minusSeconds(30);
        Instant secondTimestamp = Instant.now().minusSeconds(31);
        when(pipeline.ingest(any()))
                .thenReturn(new IngestionResult(true, UUID.randomUUID(), "accepted"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad metric"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/ingestion/readings/batch",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("readings", List.of(
                                request(deviceId, "plug-001", firstTimestamp),
                                request(deviceId, "plug-001", secondTimestamp))),
                        headers()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"status\":\"partial\"");
        assertThat(response.getBody()).contains("\"acceptedCount\":1");
        assertThat(response.getBody()).contains("\"rejectedCount\":1");
        List<IncomingTelemetryMessage> messages = capturedMessages(2);
        assertThat(messages).hasSize(2);
        assertCanonicalHttpMessage(messages.get(0), deviceId, "plug-001", firstTimestamp);
        assertCanonicalHttpMessage(messages.get(1), deviceId, "plug-001", secondTimestamp);
    }

    private List<IncomingTelemetryMessage> capturedMessages(int expectedCount) {
        ArgumentCaptor<IncomingTelemetryMessage> captor = ArgumentCaptor.forClass(IncomingTelemetryMessage.class);
        verify(pipeline, times(expectedCount)).ingest(captor.capture());
        return captor.getAllValues();
    }

    private void assertCanonicalHttpMessage(
            IncomingTelemetryMessage message,
            UUID deviceId,
            String sourceId,
            Instant timestamp) {
        assertThat(message.sourceId()).isEqualTo(sourceId);
        assertThat(message.deviceId()).isEqualTo(deviceId);
        assertThat(message.occurredAt()).isEqualTo(timestamp);
        assertThat(message.receivedAt()).isNotNull();
        assertThat(message.metrics()).containsEntry("activePowerW", 840.2);
        assertThat(message.origin().adapterType()).isEqualTo(AdapterType.GENERIC_HTTP_PUSH);
        assertThat(message.origin().protocol()).isEqualTo(ProtocolType.REST);
        assertThat(message.origin().transport()).isEqualTo(TransportType.HTTP);
        assertThat(message.context().sourceToken()).isEqualTo("source-token");
        assertThat(message.context().correlationId()).isEqualTo("correlation-1");
        assertThat(message.context().clientIp()).isEqualTo("203.0.113.10");
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("source-token");
        headers.add("X-Correlation-Id", "correlation-1");
        headers.add("X-Forwarded-For", "203.0.113.10, 198.51.100.2");
        return headers;
    }

    private Map<String, Object> request(UUID deviceId, String sourceId, Instant timestamp) {
        return Map.of(
                "deviceId", deviceId,
                "sourceId", sourceId,
                "timestamp", timestamp.toString(),
                "metrics", Map.of("activePowerW", 840.2, "voltageV", 230.1));
    }
}
