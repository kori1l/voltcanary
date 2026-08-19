package com.voltcanary.analytics.service;

import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.domain.FeatureSet;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.FeatureSetRepository;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsBaselineFeatureService {

    private static final String BASELINE_ALGORITHM_VERSION = "baseline-basic-v1";

    private final DeviceBaselineRepository baselineRepository;
    private final FeatureSetRepository featureSetRepository;
    private final AnalyticsPredictionTraceService traceService;

    public AnalyticsBaselineFeatureService(
            DeviceBaselineRepository baselineRepository,
            FeatureSetRepository featureSetRepository,
            AnalyticsPredictionTraceService traceService) {
        this.baselineRepository = baselineRepository;
        this.featureSetRepository = featureSetRepository;
        this.traceService = traceService;
    }

    public DeviceBaseline saveBaseline(UUID deviceId, String metric, String period, BaselineStats baseline, Instant now) {
        DeviceBaseline entity = new DeviceBaseline();
        entity.setId(UUID.randomUUID());
        entity.setDeviceId(deviceId);
        entity.setCalculatedAt(now);
        entity.setMetric(metric);
        entity.setPeriod(period);
        entity.setAlgorithmVersion(BASELINE_ALGORITHM_VERSION);
        entity.setMean(baseline.mean());
        entity.setMedian(baseline.median());
        entity.setP90(baseline.p90());
        entity.setMin(baseline.min());
        entity.setMax(baseline.max());
        entity.setSampleCount(baseline.sampleCount());
        entity.setBaselineJson(traceService.writeJson(Map.of(
                "metric", metric,
                "period", period,
                "algorithmVersion", BASELINE_ALGORITHM_VERSION,
                "mean", baseline.mean(),
                "median", baseline.median(),
                "p90", baseline.p90(),
                "min", baseline.min(),
                "max", baseline.max(),
                "sampleCount", baseline.sampleCount())));
        return baselineRepository.save(entity);
    }

    public FeatureSet saveFeatureSet(
            UUID deviceId,
            String metric,
            String period,
            Instant windowStart,
            Instant windowEnd,
            BaselineStats baseline,
            TelemetryChartResponse chart,
            int excludedSamples,
            List<MaintenanceWindowReadModel> maintenanceWindows) {
        FeatureSet entity = new FeatureSet();
        entity.setId(UUID.randomUUID());
        entity.setDeviceId(deviceId);
        entity.setWindowStart(windowStart);
        entity.setWindowEnd(windowEnd);
        entity.setFeaturesJson(traceService.writeJson(Map.ofEntries(
                Map.entry("metric", metric),
                Map.entry("period", period),
                Map.entry("sampleCount", baseline.sampleCount()),
                Map.entry("mean", baseline.mean()),
                Map.entry("median", baseline.median()),
                Map.entry("p90", baseline.p90()),
                Map.entry("min", baseline.min()),
                Map.entry("max", baseline.max()),
                Map.entry("latestValue", latestValue(chart)),
                Map.entry("maintenanceExcludedSampleCount", excludedSamples),
                Map.entry("maintenanceExcludedWindowIds", maintenanceWindows.stream()
                        .map(MaintenanceWindowReadModel::getMaintenanceWindowId)
                        .map(UUID::toString)
                        .toList()))));
        return featureSetRepository.save(entity);
    }

    public double latestValue(TelemetryChartResponse chart) {
        if (chart == null || chart.points().isEmpty()) {
            return 0;
        }
        return chart.points().stream()
                .max(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                .map(TelemetryChartResponse.Point::value)
                .orElse(0.0);
    }
}
