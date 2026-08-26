package com.voltcanary.ingestion;

import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=true",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.redis-rate-limit-enabled=false",
                "app.ingestion.backpressure-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class IngestionPerformanceSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryEventPublisher telemetryEventPublisher;

    @Autowired
    private SourceRegistryCache sourceRegistryCache;

    @Test
    @Timeout(10)
    void acceptsModerateBatchWithinSmokeBudget() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "perf-source";
        seedSource(deviceId, sourceId);

        List<Map<String, Object>> readings = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(300);
        for (int index = 0; index < 50; index++) {
            readings.add(request(deviceId, sourceId, start.plusSeconds(index)));
        }

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/ingestion/readings/batch",
                Map.of("readings", readings),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"acceptedCount\":50");
        verify(telemetryEventPublisher, atLeast(50)).publish(any());
    }

    @Test
    @Timeout(10)
    void repeatedSingleReadsStayWithinSmokeBudget() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "perf-single";
        seedSource(deviceId, sourceId);
        Instant start = Instant.now().minusSeconds(300);

        for (int index = 0; index < 20; index++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/ingestion/readings",
                    request(deviceId, sourceId, start.plusSeconds(index)),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        verify(telemetryEventPublisher, atLeast(20)).publish(any());
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
                        "rssi", -61));
    }

    private void seedSource(UUID deviceId, String sourceId) {
        sourceRegistryCache.put(new InternalDataSourceResponse(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                sourceId,
                "GENERIC_HTTP_PUSH",
                "REST",
                "ACTIVE",
                null,
                null), System.nanoTime());
    }
}
