package com.voltcanary.analytics;

import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecalculateResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.util.List;
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
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "app.kafka.publish-enabled=false",
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
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", points));
        when(mlClient.requestPrediction(eq(deviceId), any(Instant.class), any()))
                .thenReturn(new MlInferenceResponse(
                        deviceId,
                        "mock-model-v1",
                        0.12,
                        0.08,
                        "LOW",
                        0.5,
                        List.of("Mock inference result")));

        ResponseEntity<RecalculateResponse> recalculated = restTemplate.postForEntity(
                "/analytics/devices/{deviceId}/recalculate",
                null,
                RecalculateResponse.class,
                deviceId);

        assertThat(recalculated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recalculated.getBody()).isNotNull();
        assertThat(recalculated.getBody().anomalyCount()).isEqualTo(2);
        assertThat(recalculated.getBody().riskScore()).isEqualTo(60);
        assertThat(recalculated.getBody().riskLevel()).isEqualTo("MEDIUM");
        assertThat(recalculated.getBody().recommendationCount()).isEqualTo(2);

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

        ResponseEntity<AnomalyResponse[]> anomalies = restTemplate.getForEntity(
                "/analytics/devices/{deviceId}/anomalies",
                AnomalyResponse[].class,
                deviceId);
        assertThat(anomalies.getBody()).extracting(AnomalyResponse::type)
                .containsExactlyInAnyOrder("POWER_SPIKE", "CONSUMPTION_GROWTH");

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
        assertThat(prediction.getBody().modelVersion()).isEqualTo("mock-model-v1");
        assertThat(prediction.getBody().confidence()).isEqualTo(0.5);
    }

    private TelemetryChartResponse.Point point(Instant timestamp, double value) {
        return new TelemetryChartResponse.Point(timestamp, value);
    }
}
