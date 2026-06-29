package com.voltcanary.analytics.anomaly;

import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserRuleAnomalyDetector {

    public List<Anomaly> detect(
            UUID deviceId,
            AlertRuleSnapshot rule,
            TelemetryChartResponse chart,
            BaselineStats baseline,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            Instant now) {
        if (rule == null || !rule.enabled() || chart == null || chart.points() == null || chart.points().isEmpty()) {
            return List.of();
        }
        List<TelemetryChartResponse.Point> points = chart.points().stream()
                .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                .toList();
        boolean matched = switch (normalize(rule.conditionType())) {
            case "ABOVE_THRESHOLD" -> rule.threshold() != null && points.stream().anyMatch(point -> point.value() > rule.threshold().doubleValue());
            case "BELOW_THRESHOLD" -> rule.threshold() != null && points.stream().anyMatch(point -> point.value() < rule.threshold().doubleValue());
            case "BASELINE_MULTIPLIER" -> rule.baselineMultiplier() != null && baseline != null && baseline.sampleCount() > 0
                    && baseline.max() > baseline.mean() * rule.baselineMultiplier().doubleValue();
            case "GROWTH_RATE" -> matchesGrowth(points, rule);
            case "LONG_HIGH_RUN" -> matchesLongHighRun(points, baseline, rule);
            default -> false;
        };
        if (!matched) {
            return List.of();
        }
        return List.of(anomaly(deviceId, rule, windowStart, windowEnd, inputHash, now));
    }

    private boolean matchesGrowth(List<TelemetryChartResponse.Point> points, AlertRuleSnapshot rule) {
        int minPoints = rule.minPoints() == null ? 4 : rule.minPoints();
        if (points.size() < minPoints) {
            return false;
        }
        int midpoint = points.size() / 2;
        double firstAverage = average(points.subList(0, midpoint));
        double secondAverage = average(points.subList(midpoint, points.size()));
        double multiplier = rule.baselineMultiplier() == null ? 1.15 : rule.baselineMultiplier().doubleValue();
        return firstAverage > 0 && secondAverage > firstAverage * multiplier;
    }

    private boolean matchesLongHighRun(List<TelemetryChartResponse.Point> points, BaselineStats baseline, AlertRuleSnapshot rule) {
        int minPoints = rule.minPoints() == null ? 8 : rule.minPoints();
        if (baseline == null || baseline.sampleCount() == 0 || points.size() < minPoints) {
            return false;
        }
        long highRun = points.stream().filter(point -> point.value() > baseline.p90()).count();
        return highRun >= Math.max(4, points.size() / 2);
    }

    private double average(List<TelemetryChartResponse.Point> points) {
        return points.stream().mapToDouble(TelemetryChartResponse.Point::value).average().orElse(0);
    }

    private Anomaly anomaly(UUID deviceId, AlertRuleSnapshot rule, Instant windowStart, Instant windowEnd, String inputHash, Instant now) {
        Anomaly anomaly = new Anomaly();
        String ruleId = rule.id().toString();
        anomaly.setId(UUID.nameUUIDFromBytes((deviceId + ":" + ruleId + ":" + windowStart + ":" + windowEnd + ":" + inputHash)
                .getBytes(StandardCharsets.UTF_8)));
        anomaly.setDeviceId(deviceId);
        anomaly.setType(rule.ruleType());
        anomaly.setSeverity(normalizeSeverity(rule.severity()));
        anomaly.setScore(score(rule.severity()));
        anomaly.setDescription(description(rule));
        anomaly.setRuleId(ruleId);
        anomaly.setRuleVersion(String.valueOf(rule.version()));
        anomaly.setWindowStart(windowStart);
        anomaly.setWindowEnd(windowEnd);
        anomaly.setInputHash(inputHash);
        anomaly.setStatus("ACTIVE");
        anomaly.setDetectedAt(now);
        return anomaly;
    }

    private String description(AlertRuleSnapshot rule) {
        return switch (normalize(rule.ruleType())) {
            case "POWER_SPIKE" -> "A configured power spike rule matched recent telemetry.";
            case "CONSUMPTION_GROWTH" -> "A configured consumption growth rule matched recent telemetry.";
            case "LONG_CYCLE" -> "A configured long-cycle rule matched recent telemetry.";
            default -> "A configured alert rule matched recent telemetry.";
        };
    }

    private double score(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "CRITICAL" -> 90;
            case "HIGH" -> 70;
            case "WARNING" -> 40;
            default -> 15;
        };
    }

    private String normalizeSeverity(String severity) {
        String normalized = normalize(severity);
        if ("MEDIUM".equals(normalized)) {
            return "WARNING";
        }
        if ("LOW".equals(normalized)) {
            return "INFO";
        }
        return normalized.isBlank() ? "WARNING" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
