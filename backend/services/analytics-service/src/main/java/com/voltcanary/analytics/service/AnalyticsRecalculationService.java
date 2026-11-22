package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.Recommendation;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.FeatureSetResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnalyticsRecalculationService {
    private final AnalyticsInputReader inputs;
    private final AnalyticsComputation computation;
    private final AnalyticsPrediction predictions;
    private final AnalyticsResultStore results;
    private final AnalyticsReadService reads;
    private final MeterRegistry meterRegistry;
    private final DeviceHistoryDeletion deletions;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecalculationResult recalculate(UUID deviceId) { return recalculate(deviceId, Instant.now(), null); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecalculationResult recalculate(UUID deviceId, Instant windowEnd) { return recalculate(deviceId, windowEnd, null); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecalculationResult recalculate(UUID deviceId, Instant windowEnd, AnalyticsJob job) {
        if (!deletions.writable(deviceId)) return RecalculationResult.skipped(inputs.windowStart(windowEnd), windowEnd, "", "DEVICE_DELETED");
        var sample = Timer.start(meterRegistry);
        String status = "failure";
        try {
            var input = inputs.read(deviceId, windowEnd);
            var calculated = computation.calculate(input);
            if (calculated.skipped()) {
                status = "skipped";
                meterRegistry.counter("analytics.maintenance.jobs_skipped", "reason", "full_coverage").increment();
                return RecalculationResult.skipped(input.from(), input.to(), input.inputHash(), "MAINTENANCE_FULL_COVERAGE");
            }
            var context = input.context();
            var prepared = predictions.prepare(deviceId, calculated.primaryChart(), calculated.primaryBaseline(), calculated.risk(),
                    input.now(), context.windows(), input.from(), input.to(), input.inputHash(), context.accountId(),
                    context.deviceType(), context.settings(), context.policy());
            var recommendations = computation.recommendations(deviceId, calculated.anomalies(), prepared.primary(), input.now());
            var committed = results.save(deviceId, job, input.now(), calculated.baselines(), calculated.features(), calculated.anomalies(),
                    calculated.risk(), prepared.all(), recommendations);
            if (committed != AnalyticsResultStore.Outcome.SAVED) {
                status = "skipped";
                return RecalculationResult.skipped(input.from(), input.to(), input.inputHash(), committed.name());
            }
            calculated.anomalies().forEach(anomaly -> meterRegistry.counter("analytics.anomalies.detected",
                    "type", anomaly.getType() == null ? "unknown" : anomaly.getType(),
                    "severity", anomaly.getSeverity() == null ? "unknown" : anomaly.getSeverity()).increment());
            status = "success";
            log.info("analyticsRecalculationCompleted deviceId={} windowStart={} windowEnd={} inputHash={} anomalies={} riskLevel={} riskScore={}",
                    deviceId, input.from(), input.to(), input.inputHash(), calculated.anomalies().size(), calculated.risk().getLevel(), calculated.risk().getScore());
            return new RecalculationResult(calculated.baselines().isEmpty() ? null : calculated.baselines().getFirst(), calculated.anomalies(),
                    calculated.risk(), recommendations, prepared.primary(), input.from(), input.to(), input.inputHash(), false, null);
        } finally {
            sample.stop(meterRegistry.timer("analytics.recalculation.duration", "status", status));
        }
    }

    @Transactional(readOnly = true)
    public Optional<BaselineResponse> baseline(UUID deviceId) {
        return reads.baseline(deviceId);
    }

    @Transactional(readOnly = true)
    public List<AnomalyResponse> anomalies(UUID deviceId) {
        return reads.anomalies(deviceId);
    }

    @Transactional(readOnly = true)
    public List<FeatureSetResponse> features(UUID deviceId) {
        return reads.features(deviceId);
    }

    @Transactional(readOnly = true)
    public Optional<RiskResponse> risk(UUID deviceId) {
        return reads.risk(deviceId);
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> recommendations(UUID deviceId) {
        return reads.recommendations(deviceId);
    }

    @Transactional(readOnly = true)
    public Optional<PredictionResponse> latestPrediction(UUID deviceId) {
        return reads.latestPrediction(deviceId);
    }

    public record RecalculationResult(
            DeviceBaseline baseline,
            List<Anomaly> anomalies,
            RiskScore riskScore,
            List<Recommendation> recommendations,
            PredictionResult prediction,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            boolean skipped,
            String skipReason) {

        static RecalculationResult skipped(Instant windowStart, Instant windowEnd, String inputHash, String skipReason) {
            return new RecalculationResult(
                    null,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    windowStart,
                    windowEnd,
                    inputHash,
                    true,
                    skipReason);
        }
    }

}
