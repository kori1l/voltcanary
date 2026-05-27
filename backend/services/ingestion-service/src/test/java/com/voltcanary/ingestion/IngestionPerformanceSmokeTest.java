package com.voltcanary.ingestion;

import com.voltcanary.ingestion.device.DeviceValidationClient;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
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
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=true",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.backpressure-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class IngestionPerformanceSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private DeviceValidationClient deviceValidationClient;

    @MockitoBean
    private TelemetryEventPublisher telemetryEventPublisher;

    @Test
    @Timeout(10)
    void acceptsModerateBatchWithinSmokeBudget() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "perf-source";
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(true);

        List<Map<String, Object>> readings = new ArrayList<>();
        Instant start = Instant.parse("2026-05-12T13:00:00Z");
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
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(true);
        Instant start = Instant.parse("2026-05-12T14:00:00Z");

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
}
