package com.voltcanary.analytics.service;

import com.voltcanary.analytics.anomaly.AnomalyDetector;
import com.voltcanary.analytics.anomaly.UserRuleAnomalyDetector;
import com.voltcanary.analytics.baseline.BaselineCalculator;
import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.domain.FeatureSet;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.Recommendation;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.recommendation.RecommendationGenerator;
import com.voltcanary.analytics.risk.RiskScoreCalculator;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsComputation {

    public record Computed(
        List<DeviceBaseline> baselines,
        List<FeatureSet> features,
        List<Anomaly> anomalies,
        RiskScore risk,
        BaselineStats primaryBaseline,
        TelemetryChartResponse primaryChart,
        boolean skipped
    ) {}

    private final BaselineCalculator baselineCalculator;
    private final AnomalyDetector anomalyDetector;
    private final UserRuleAnomalyDetector userRuleAnomalyDetector;
    private final RiskScoreCalculator riskScoreCalculator;
    private final RecommendationGenerator recommendationGenerator;
    private final AnalyticsBaselineFeatureService features;
    private final AnalyticsMlRecommendationService mlRecommendations;

    public Computed calculate(AnalyticsInputReader.Input input) {
        var stats = new ArrayList<BaselineStats>();
        var statsByMetric = new HashMap<String, BaselineStats>();
        var allValues = new ArrayList<Double>();
        for (int index = 0; index < input.metrics().size(); index++) {
            var values = values(input.charts().get(index));
            var baseline = baselineCalculator.calculate(values);
            stats.add(baseline);
            statsByMetric.put(input.metrics().get(index), baseline);
            allValues.addAll(values);
        }
        var primary = stats.isEmpty() ? baselineCalculator.calculate(List.of()) : stats.getFirst();
        var primaryChart = input.charts().isEmpty() ? null : input.charts().getFirst();
        if (allValues.isEmpty() && input.excludedSamples() > 0) {
            return new Computed(List.of(), List.of(), List.of(), null, primary, primaryChart, true);
        }
        var baselines = new ArrayList<DeviceBaseline>();
        var featureSets = new ArrayList<FeatureSet>();
        var anomalies = new ArrayList<Anomaly>();
        for (int index = 0; index < input.metrics().size(); index++) {
            String metric = input.metrics().get(index);
            var baseline = stats.get(index);
            var chart = input.charts().get(index);
            baselines.add(
                features.baseline(input.device(), metric, input.period(), baseline, input.now())
            );
            featureSets.add(
                features.featureSet(
                    input.device(),
                    metric,
                    input.period(),
                    input.from(),
                    input.to(),
                    baseline,
                    chart,
                    input.excludedSamples(),
                    input.context().windows()
                )
            );
            anomalies.addAll(
                anomalyDetector.detect(
                    input.device(),
                    chart,
                    baseline,
                    input.from(),
                    input.to(),
                    input.inputHash(),
                    input.now()
                )
            );
        }
        for (var rule : input.rules()) {
            if (rule.metric() == null || rule.metric().isBlank()) continue;
            var chart = input.chartByMetric().get(rule.metric());
            var baseline = statsByMetric.computeIfAbsent(rule.metric(), metric ->
                baselineCalculator.calculate(values(chart))
            );
            anomalies.addAll(
                userRuleAnomalyDetector.detect(
                    input.device(),
                    rule,
                    chart,
                    baseline,
                    input.from(),
                    input.to(),
                    input.inputHash(),
                    input.now()
                )
            );
        }
        var risk = riskScoreCalculator.calculate(
            input.device(),
            anomalies,
            allValues.size(),
            input.from(),
            input.to(),
            input.inputHash(),
            input.now()
        );
        return new Computed(
            List.copyOf(baselines),
            List.copyOf(featureSets),
            List.copyOf(anomalies),
            risk,
            primary,
            primaryChart,
            false
        );
    }

    public List<Recommendation> recommendations(
        UUID device,
        List<Anomaly> anomalies,
        PredictionResult prediction,
        Instant now
    ) {
        var result = new ArrayList<>(recommendationGenerator.generate(device, anomalies, now));
        result.addAll(mlRecommendations.mlRecommendations(device, prediction, now));
        return List.copyOf(result);
    }

    private List<Double> values(TelemetryChartResponse chart) {
        return chart == null || chart.points() == null
            ? List.of()
            : chart.points().stream().map(TelemetryChartResponse.Point::value).sorted().toList();
    }
}
