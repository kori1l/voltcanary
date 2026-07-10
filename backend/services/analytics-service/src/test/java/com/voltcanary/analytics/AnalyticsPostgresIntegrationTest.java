package com.voltcanary.analytics;

import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlExcludedInterval;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=false",
                "app.analytics.authorization.enabled=false",
                "app.analytics.jobs.async=false",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class AnalyticsPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryClient telemetryClient;

    @MockitoBean
    private MlClient mlClient;

    @Test
    void recalculatePersistsPredictionResultInPostgres() {
        UUID deviceId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-13T00:00:00Z");
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", List.of(
                        new TelemetryChartResponse.Point(start, 100),
                        new TelemetryChartResponse.Point(start.plusSeconds(60), 400))));
        when(mlClient.requestPrediction(
                eq(deviceId),
                any(Instant.class),
                any(),
                org.mockito.ArgumentMatchers.<List<MlExcludedInterval>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(new MlInferenceResponse(
                        deviceId,
                        "voltcanary-baseline-heuristic",
                        "baseline-heuristic-v1",
                        "baseline_heuristic",
                        "READY_HEURISTIC",
                        "BASELINE_HEURISTIC",
                        0.12,
                        0.08,
                        "LOW",
                        0.5,
                        List.of(Map.of("metric", "activePowerW", "score", 0.12)),
                        List.of("Mock"),
                        List.of("Continue normal monitoring."),
                        "ml-input-hash"));

        restTemplate.postForEntity("/analytics/devices/{deviceId}/recalculate", null, String.class, deviceId);

        ResponseEntity<PredictionResponse> prediction = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/predictions/latest",
                PredictionResponse.class,
                deviceId);
        assertThat(prediction.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prediction.getBody()).isNotNull();
        assertThat(prediction.getBody().modelName()).isEqualTo("voltcanary-baseline-heuristic");
        assertThat(prediction.getBody().modelVersion()).isEqualTo("baseline-heuristic-v1");
        assertThat(prediction.getBody().predictionStatus()).isEqualTo("BASELINE_HEURISTIC");
        assertThat(prediction.getBody().confidence()).isEqualTo(0.5);
        assertThat(prediction.getBody().inputHash()).isEqualTo("ml-input-hash");
    }
}
