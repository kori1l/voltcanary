package com.voltcanary.analytics;

import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.FeatureSetResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecalculateResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlExcludedInterval;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.service.AnalyticsJobService;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import java.time.Duration;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "app.kafka.publish-enabled=false",
                "app.analytics.authorization.enabled=false",
                "app.analytics.jobs.async=false",
                "spring.datasource.url=jdbc:h2:mem:analytics-recalculation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true"
        })
class AnalyticsRecalculationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryClient telemetryClient;

    @MockitoBean
    private MlClient mlClient;

    @Autowired
    private DeviceBaselineRepository baselineRepository;

    @Autowired
    private AnomalyRepository anomalyRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private PredictionResultRepository predictionResultRepository;

    @Autowired
    private AnalyticsJobService jobService;

    @Autowired
    private AnalyticsJobRepository jobRepository;

    @Autowired
    private MaintenanceWindowProjectionService maintenanceWindowProjectionService;

    @Test
    void recalculateCreatesBaselineAnomaliesRiskAndRecommendations() {
        UUID deviceId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-13T00:00:00Z");
        List<TelemetryChartResponse.Point> points = List.of(
                point(start, 100),
                point(start.plusSeconds(60), 100),
                point(start.plusSeconds(120), 100),
                point(start.plusSeconds(180), 100),
                point(start.plusSeconds(240), 100),
                point(start.plusSeconds(300), 100),
                point(start.plusSeconds(360), 100),
                point(start.plusSeconds(420), 100),
                point(start.plusSeconds(480), 100),
                point(start.plusSeconds(540), 400));
        List<TelemetryChartResponse.Point> nextPoints = List.of(
                point(start, 120),
                point(start.plusSeconds(60), 120),
                point(start.plusSeconds(120), 120),
                point(start.plusSeconds(180), 120),
                point(start.plusSeconds(240), 120),
                point(start.plusSeconds(300), 120),
                point(start.plusSeconds(360), 120),
                point(start.plusSeconds(420), 120),
                point(start.plusSeconds(480), 120),
                point(start.plusSeconds(540), 480));
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(
                        new TelemetryChartResponse(deviceId, "activePowerW", points),
                        new TelemetryChartResponse(deviceId, "activePowerW", nextPoints));
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
                        List.of(Map.of("metric", "activePowerW", "score", 0.12, "severity", "LOW")),
                        List.of("Mock inference result"),
                        List.of("Continue normal monitoring."),
                        "ml-input-hash"));

        Instant requestStartedAt = Instant.now();
        ResponseEntity<RecalculateResponse> recalculated = restTemplate.postForEntity(
                "/analytics/devices/{deviceId}/recalculate",
                null,
                RecalculateResponse.class,
                deviceId);

        assertThat(recalculated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recalculated.getBody()).isNotNull();
        assertThat(recalculated.getBody().status()).isEqualTo("completed");
        assertThat(recalculated.getBody().jobId()).isNotNull();
        org.mockito.ArgumentCaptor<Instant> windowEndCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(telemetryClient, atLeastOnce()).readings(
                eq(deviceId),
                eq("activePowerW"),
                any(Instant.class),
                windowEndCaptor.capture());
        assertThat(windowEndCaptor.getAllValues().getFirst()).isAfterOrEqualTo(requestStartedAt);

        ResponseEntity<BaselineResponse> baseline = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/baseline",
                BaselineResponse.class,
                deviceId);
        assertThat(baseline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(baseline.getBody()).isNotNull();
        assertThat(baseline.getBody().mean()).isEqualTo(130);
        assertThat(baseline.getBody().median()).isEqualTo(100);
        assertThat(baseline.getBody().p90()).isEqualTo(100);
        assertThat(baseline.getBody().sampleCount()).isEqualTo(10);
        assertThat(baselineRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)).hasValueSatisfying(entity -> {
            assertThat(entity.getMetric()).isEqualTo("activePowerW");
            assertThat(entity.getPeriod()).isEqualTo("last_24h");
            assertThat(entity.getAlgorithmVersion()).isEqualTo("baseline-basic-v1");
            assertThat(entity.getMean()).isEqualTo(130);
            assertThat(entity.getSampleCount()).isEqualTo(10);
        });

        ResponseEntity<AnomalyResponse[]> anomalies = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/anomalies",
                AnomalyResponse[].class,
                deviceId);
        assertThat(anomalies.getBody()).extracting(AnomalyResponse::type)
                .containsExactlyInAnyOrder("POWER_SPIKE", "CONSUMPTION_GROWTH");

        ResponseEntity<FeatureSetResponse[]> features = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/features",
                FeatureSetResponse[].class,
                deviceId);
        assertThat(features.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(features.getBody()).hasSize(1);
        assertThat(features.getBody()[0].features())
                .containsEntry("metric", "activePowerW")
                .containsEntry("sampleCount", 10);

        ResponseEntity<RiskResponse> risk = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/risk",
                RiskResponse.class,
                deviceId);
        assertThat(risk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(risk.getBody()).isNotNull();
        assertThat(risk.getBody().explanations()).hasSize(2);

        ResponseEntity<RecommendationResponse[]> recommendations = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/recommendations",
                RecommendationResponse[].class,
                deviceId);
        assertThat(recommendations.getBody()).hasSize(2);

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
        assertThat(prediction.getBody().signals()).hasSize(1);

        restTemplate.postForEntity(
                "/analytics/devices/{deviceId}/recalculate",
                null,
                RecalculateResponse.class,
                deviceId);

        assertThat(anomalyRepository.findTop100ByDeviceIdAndStatusOrderByDetectedAtDesc(deviceId, "ACTIVE"))
                .hasSize(2)
                .allSatisfy(anomaly -> {
                    assertThat(anomaly.getRuleId()).isNotBlank();
                    assertThat(anomaly.getRuleVersion()).isEqualTo("rules-v1");
                });
        assertThat(anomalyRepository.findTop100ByDeviceIdAndStatusOrderByDetectedAtDesc(deviceId, "SUPERSEDED"))
                .hasSize(2);
        assertThat(recommendationRepository.findTop100ByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "ACTIVE"))
                .hasSize(2);
        assertThat(recommendationRepository.findTop100ByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "SUPERSEDED"))
                .hasSize(2);
    }

    @Test
    void eventTriggeredRecalculationUsesDeterministicWindowBucket() {
        UUID deviceId = UUID.randomUUID();
        Instant eventTime = Instant.parse("2026-05-29T10:17:42Z");
        Instant expectedWindowEnd = Instant.parse("2026-05-29T11:00:00Z");
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", List.of(
                        point(eventTime.minusSeconds(60), 100),
                        point(eventTime, 140))));

        var response = jobService.enqueueRecalculation(deviceId, eventTime);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(telemetryClient).readings(
                eq(deviceId),
                eq("activePowerW"),
                eq(expectedWindowEnd.minus(Duration.ofHours(24))),
                eq(expectedWindowEnd));
        assertThat(jobRepository.findById(response.id())).hasValueSatisfying(job -> {
            assertThat(job.getWindowStart()).isEqualTo(expectedWindowEnd.minus(Duration.ofHours(24)));
            assertThat(job.getWindowEnd()).isEqualTo(expectedWindowEnd);
            assertThat(job.getInputHash()).isNotBlank();
        });
    }

    @Test
    void recalculatePersistsUnavailablePredictionWhenMlReturnsNull() {
        UUID deviceId = UUID.randomUUID();
        Instant start = Instant.parse("2026-05-13T00:00:00Z");
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", List.of(
                        point(start, 100),
                        point(start.plusSeconds(60), 120))));
        when(mlClient.requestPrediction(
                eq(deviceId),
                any(Instant.class),
                any(),
                org.mockito.ArgumentMatchers.<List<MlExcludedInterval>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(null);

        ResponseEntity<RecalculateResponse> recalculated = restTemplate.postForEntity(
                "/analytics/devices/{deviceId}/recalculate",
                null,
                RecalculateResponse.class,
                deviceId);

        assertThat(recalculated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recalculated.getBody()).isNotNull();
        assertThat(recalculated.getBody().status()).isEqualTo("completed");
        assertThat(predictionResultRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId))
                .hasValueSatisfying(prediction -> {
                    assertThat(prediction.getModelName()).isEqualTo("ml-unavailable");
                    assertThat(prediction.getStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(prediction.getInputHash()).isNotBlank();
                });
    }

    @Test
    void recalculatePassesMaintenanceExclusionsToMl() {
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID maintenanceWindowId = UUID.randomUUID();
        Instant start = Instant.now();
        maintenanceWindowProjectionService.project(new MaintenanceWindowChangedEvent(
                UUID.randomUUID(),
                "v1",
                "MAINTENANCE_WINDOW_CREATED",
                maintenanceWindowId,
                accountId,
                deviceId,
                "Planned work",
                null,
                start.minusSeconds(60),
                start.plusSeconds(600),
                true,
                false,
                true,
                null,
                null,
                1,
                null,
                "device-service",
                UUID.randomUUID(),
                start.minusSeconds(120)));
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", List.of(
                        point(start, 100),
                        point(start.plusSeconds(60), 120))));

        restTemplate.postForEntity("/analytics/devices/{deviceId}/recalculate", null, RecalculateResponse.class, deviceId);

        org.mockito.ArgumentCaptor<List<MlExcludedInterval>> exclusionsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mlClient).requestPrediction(
                eq(deviceId),
                any(Instant.class),
                any(),
                exclusionsCaptor.capture(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any());
        assertThat(exclusionsCaptor.getValue())
                .extracting(MlExcludedInterval::maintenanceWindowId)
                .containsExactly(maintenanceWindowId);
    }

    private TelemetryChartResponse.Point point(Instant timestamp, double value) {
        return new TelemetryChartResponse.Point(timestamp, value);
    }
}
