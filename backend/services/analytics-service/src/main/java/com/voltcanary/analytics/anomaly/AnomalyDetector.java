package com.voltcanary.analytics.anomaly;

import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.config.AnalyticsRuleProperties;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnomalyDetector {

    public static final String POWER_SPIKE_RULE = "POWER_SPIKE";
    public static final String CONSUMPTION_GROWTH_RULE = "CONSUMPTION_GROWTH";
    public static final String LONG_CYCLE_RULE = "LONG_CYCLE";

    private final AnalyticsRuleProperties rules;

    public AnomalyDetector(AnalyticsRuleProperties rules) {
        this.rules = rules;
    }

    public List<Anomaly> detect(
            UUID deviceId,
            TelemetryChartResponse chart,
            BaselineStats baseline,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now) {
        if (chart == null || chart.points().isEmpty() || baseline.sampleCount() == 0) {
            return List.of();
        }

        List<Anomaly> anomalies = new ArrayList<>();
        detectPowerSpike(deviceId, baseline, windowStart, windowEnd, inputHash, now, anomalies);

        List<TelemetryChartResponse.Point> points = chart.points().stream()
                .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                .toList();
        detectConsumptionGrowth(deviceId, points, windowStart, windowEnd, inputHash, now, anomalies);
        detectLongCycle(deviceId, baseline, points, windowStart, windowEnd, inputHash, now, anomalies);
        return anomalies;
    }

    private void detectPowerSpike(
            UUID deviceId,
            BaselineStats baseline,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now,
            List<Anomaly> anomalies) {
        AnalyticsRuleProperties.PowerSpike rule = rules.getPowerSpike();
        if (!rule.isEnabled()) {
            return;
        }
        double spikeThreshold = Math.max(
                baseline.median() * rule.getMedianMultiplier(),
                baseline.mean() * rule.getMeanMultiplier());
        if (baseline.max() > spikeThreshold && baseline.max() > baseline.mean()) {
            anomalies.add(anomaly(
                    deviceId,
                    POWER_SPIKE_RULE,
                    rule.getSeverity(),
                    rule.getScore(),
                    "A power spike was detected above the normal range. Check device load and power conditions.",
                    windowStart,
                    windowEnd,
                    inputHash,
                    now));
        }
    }

    private void detectConsumptionGrowth(
            UUID deviceId,
            List<TelemetryChartResponse.Point> points,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now,
            List<Anomaly> anomalies) {
        AnalyticsRuleProperties.ConsumptionGrowth rule = rules.getConsumptionGrowth();
        if (!rule.isEnabled() || points.size() < rule.getMinPoints()) {
            return;
        }
        int midpoint = points.size() / 2;
        double firstAverage = average(points.subList(0, midpoint));
        double secondAverage = average(points.subList(midpoint, points.size()));
        if (firstAverage > 0 && secondAverage > firstAverage * rule.getGrowthMultiplier()) {
            anomalies.add(anomaly(
                    deviceId,
                    CONSUMPTION_GROWTH_RULE,
                    rule.getSeverity(),
                    rule.getScore(),
                    "Average consumption increased compared to baseline. Check operating mode and external conditions. If the anomaly repeats, maintenance is recommended.",
                    windowStart,
                    windowEnd,
                    inputHash,
                    now));
        }
    }

    private void detectLongCycle(
            UUID deviceId,
            BaselineStats baseline,
            List<TelemetryChartResponse.Point> points,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now,
            List<Anomaly> anomalies) {
        AnalyticsRuleProperties.LongCycle rule = rules.getLongCycle();
        if (!rule.isEnabled() || points.size() < rule.getMinPoints()) {
            return;
        }
        long highRun = points.stream().filter(point -> point.value() > baseline.p90()).count();
        if (highRun >= Math.max(rule.getMinHighPoints(), points.size() / 2)) {
            anomalies.add(anomaly(
                    deviceId,
                    LONG_CYCLE_RULE,
                    rule.getSeverity(),
                    rule.getScore(),
                    "Power stayed above the normal range for an extended portion of the window.",
                    windowStart,
                    windowEnd,
                    inputHash,
                    now));
        }
    }

    private double average(List<TelemetryChartResponse.Point> points) {
        return points.stream().mapToDouble(TelemetryChartResponse.Point::value).average().orElse(0);
    }

    private Anomaly anomaly(
            UUID deviceId,
            String ruleId,
            String severity,
            double score,
            String description,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now) {
        Anomaly anomaly = new Anomaly();
        anomaly.setId(UUID.nameUUIDFromBytes((deviceId + ":" + ruleId + ":" + windowStart + ":" + windowEnd + ":" + inputHash)
                .getBytes(StandardCharsets.UTF_8)));
        anomaly.setDeviceId(deviceId);
        anomaly.setType(ruleId);
        anomaly.setSeverity(severity);
        anomaly.setScore(score);
        anomaly.setDescription(description);
        anomaly.setRuleId(ruleId);
        anomaly.setRuleVersion(rules.getVersion());
        anomaly.setWindowStart(windowStart);
        anomaly.setWindowEnd(windowEnd);
        anomaly.setInputHash(inputHash);
        anomaly.setStatus("ACTIVE");
        anomaly.setDetectedAt(now);
        return anomaly;
    }
}
