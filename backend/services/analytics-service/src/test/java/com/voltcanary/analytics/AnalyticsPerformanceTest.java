package com.voltcanary.analytics;

import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlExcludedInterval;
import com.voltcanary.analytics.repository.AnalyticsEventOutboxRepository;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.FeatureSetRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.repository.RiskScoreRepository;
import com.voltcanary.analytics.service.AnalyticsRecalculationService;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-performance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AnalyticsPerformanceTest {

    @Autowired
    private AnalyticsRecalculationService recalculationService;

    @MockitoBean
    private TelemetryClient telemetryClient;

    @MockitoBean
    private MlClient mlClient;

    @Autowired
    private DeviceBaselineRepository baselineRepository;

    @Autowired
    private FeatureSetRepository featureSetRepository;

    @Autowired
    private AnomalyRepository anomalyRepository;

    @Autowired
    private RiskScoreRepository riskScoreRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private PredictionResultRepository predictionResultRepository;

    @Autowired
    private AnalyticsEventOutboxRepository outboxRepository;

    @Test
    void recalculationHandlesSmallMediumAndLargeTelemetryWindowsWithBoundedWrites() {
        verifyWindow("small", 10);
        verifyWindow("medium", 250);
        verifyWindow("large", 1_000);
    }

    private void verifyWindow(String label, int sampleCount) {
        UUID deviceId = UUID.randomUUID();
        when(telemetryClient.readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class)))
                .thenReturn(new TelemetryChartResponse(deviceId, "activePowerW", points(sampleCount)));
        when(mlClient.requestPrediction(
                eq(deviceId),
                any(Instant.class),
                any(),
                org.mockito.ArgumentMatchers.<List<MlExcludedInterval>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(null);

        long baselinesBefore = baselineRepository.count();
        long featuresBefore = featureSetRepository.count();
        long anomaliesBefore = anomalyRepository.count();
        long riskBefore = riskScoreRepository.count();
        long recommendationsBefore = recommendationRepository.count();
        long predictionsBefore = predictionResultRepository.count();
        long outboxBefore = outboxRepository.count();
        Instant startedAt = Instant.now();

        recalculationService.recalculate(deviceId);

        Duration elapsed = Duration.between(startedAt, Instant.now());
        assertThat(elapsed)
                .as(label + " telemetry window should complete within a broad regression budget")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(baselineRepository.count() - baselinesBefore).isEqualTo(1);
        assertThat(featureSetRepository.count() - featuresBefore).isEqualTo(1);
        assertThat(riskScoreRepository.count() - riskBefore).isEqualTo(1);
        assertThat(predictionResultRepository.count() - predictionsBefore).isEqualTo(1);
        assertThat(recommendationRepository.count() - recommendationsBefore).isLessThanOrEqualTo(3);
        assertThat(anomalyRepository.count() - anomaliesBefore).isLessThanOrEqualTo(3);
        assertThat(outboxRepository.count() - outboxBefore).isLessThanOrEqualTo(4);
        verify(telemetryClient).readings(eq(deviceId), eq("activePowerW"), any(Instant.class), any(Instant.class));
    }

    private List<TelemetryChartResponse.Point> points(int sampleCount) {
        Instant start = Instant.parse("2026-05-12T12:00:00Z");
        return IntStream.range(0, sampleCount)
                .mapToObj(index -> new TelemetryChartResponse.Point(
                        start.plusSeconds(index * 60L),
                        index == sampleCount - 1 ? 420.0 : 120.0))
                .toList();
    }
}
