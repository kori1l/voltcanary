package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.RuleEvaluationRequest;
import com.voltcanary.analytics.dto.RuleEvaluationResponse;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleEvaluationService {

    private static final int MAX_EVIDENCE = 20;

    private final TelemetryClient telemetryClient;

    public RuleEvaluationService(TelemetryClient telemetryClient) {
        this.telemetryClient = telemetryClient;
    }

    public RuleEvaluationResponse evaluate(RuleEvaluationRequest request) {
        validate(request);
        Instant to = request.to() == null ? Instant.now() : request.to();
        Instant from = request.from() == null ? to.minus(Duration.ofHours(24)) : request.from();
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        TelemetryChartResponse chart = telemetryClient.readings(request.deviceId(), request.metric(), from, to);
        List<TelemetryChartResponse.Point> points = chart == null || chart.points() == null
                ? List.of()
                : chart.points().stream()
                        .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                        .toList();
        if (points.isEmpty()) {
            return response(false, 0, request, "NO_DATA", "No telemetry samples were found for the selected window.", List.of());
        }

        return switch (normalize(request.conditionType())) {
            case "ABOVE_THRESHOLD" -> thresholdResponse(points, request, true);
            case "BELOW_THRESHOLD" -> thresholdResponse(points, request, false);
            case "BASELINE_MULTIPLIER" -> baselineMultiplierResponse(points, request);
            case "GROWTH_RATE" -> growthRateResponse(points, request);
            case "LONG_HIGH_RUN" -> longHighRunResponse(points, request);
            case "MISSING_TELEMETRY" -> response(false, 0, request, "NOT_SUPPORTED", "Missing telemetry rules need gap-aware source heartbeat evaluation.", List.of());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported conditionType");
        };
    }

    private RuleEvaluationResponse thresholdResponse(List<TelemetryChartResponse.Point> points, RuleEvaluationRequest request, boolean above) {
        double threshold = request.threshold();
        List<RuleEvaluationResponse.Evidence> evidence = points.stream()
                .filter(point -> above ? point.value() > threshold : point.value() < threshold)
                .map(point -> evidence(point, request.metric(), threshold))
                .toList();
        return response(!evidence.isEmpty(), evidence.size(), request, "EVALUATED", thresholdDescription(request, evidence.size()), limitEvidence(evidence));
    }

    private RuleEvaluationResponse baselineMultiplierResponse(List<TelemetryChartResponse.Point> points, RuleEvaluationRequest request) {
        double baseline = average(points);
        double threshold = baseline * request.baselineMultiplier();
        List<RuleEvaluationResponse.Evidence> evidence = points.stream()
                .filter(point -> point.value() > threshold)
                .map(point -> evidence(point, request.metric(), threshold))
                .toList();
        return response(!evidence.isEmpty(), evidence.size(), request, "EVALUATED", thresholdDescription(request, evidence.size()), limitEvidence(evidence));
    }

    private RuleEvaluationResponse growthRateResponse(List<TelemetryChartResponse.Point> points, RuleEvaluationRequest request) {
        int minPoints = request.minPoints() == null ? 4 : request.minPoints();
        if (points.size() < minPoints) {
            return response(false, 0, request, "INSUFFICIENT_DATA", "Not enough telemetry samples to evaluate the growth rule.", List.of());
        }
        int midpoint = points.size() / 2;
        double firstAverage = average(points.subList(0, midpoint));
        double secondAverage = average(points.subList(midpoint, points.size()));
        double multiplier = request.baselineMultiplier() == null ? 1.15 : request.baselineMultiplier();
        boolean matched = firstAverage > 0 && secondAverage > firstAverage * multiplier;
        double threshold = firstAverage * multiplier;
        List<RuleEvaluationResponse.Evidence> evidence = matched
                ? points.subList(midpoint, points.size()).stream().map(point -> evidence(point, request.metric(), threshold)).toList()
                : List.of();
        return response(matched, evidence.size(), request, "EVALUATED", growthDescription(evidence.size()), limitEvidence(evidence));
    }

    private RuleEvaluationResponse longHighRunResponse(List<TelemetryChartResponse.Point> points, RuleEvaluationRequest request) {
        int minPoints = request.minPoints() == null ? 8 : request.minPoints();
        if (points.size() < minPoints) {
            return response(false, 0, request, "INSUFFICIENT_DATA", "Not enough telemetry samples to evaluate the long-cycle rule.", List.of());
        }
        double threshold = percentile(points, 0.90);
        List<RuleEvaluationResponse.Evidence> evidence = points.stream()
                .filter(point -> point.value() > threshold)
                .map(point -> evidence(point, request.metric(), threshold))
                .toList();
        boolean matched = evidence.size() >= Math.max(4, points.size() / 2);
        return response(matched, evidence.size(), request, "EVALUATED", longRunDescription(evidence.size()), limitEvidence(evidence));
    }

    private void validate(RuleEvaluationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule evaluation request is required");
        }
        if (request.deviceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        if (request.metric() == null || request.metric().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric is required");
        }
        String condition = normalize(request.conditionType());
        if (("ABOVE_THRESHOLD".equals(condition) || "BELOW_THRESHOLD".equals(condition)) && request.threshold() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threshold is required");
        }
        if ("BASELINE_MULTIPLIER".equals(condition) && request.baselineMultiplier() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineMultiplier is required");
        }
    }

    private RuleEvaluationResponse response(
            boolean matched,
            int matchCount,
            RuleEvaluationRequest request,
            String status,
            String description,
            List<RuleEvaluationResponse.Evidence> evidence) {
        return new RuleEvaluationResponse(
                matched,
                matchCount,
                matched,
                request.severity() == null || request.severity().isBlank() ? "WARNING" : request.severity(),
                title(request.ruleType()),
                description,
                status,
                evidence);
    }

    private RuleEvaluationResponse.Evidence evidence(TelemetryChartResponse.Point point, String metric, double threshold) {
        return new RuleEvaluationResponse.Evidence(point.timestamp(), metric, point.value(), threshold, null);
    }

    private List<RuleEvaluationResponse.Evidence> limitEvidence(List<RuleEvaluationResponse.Evidence> evidence) {
        return evidence.stream().limit(MAX_EVIDENCE).toList();
    }

    private double average(List<TelemetryChartResponse.Point> points) {
        return points.stream().mapToDouble(TelemetryChartResponse.Point::value).average().orElse(0);
    }

    private double percentile(List<TelemetryChartResponse.Point> points, double percentile) {
        List<Double> values = points.stream().map(TelemetryChartResponse.Point::value).sorted().toList();
        int index = Math.min(values.size() - 1, Math.max(0, (int) Math.floor((values.size() - 1) * percentile)));
        return values.get(index);
    }

    private String thresholdDescription(RuleEvaluationRequest request, int matchCount) {
        return matchCount == 0
                ? "No telemetry samples matched the configured rule."
                : formatMetric(request.metric()) + " matched the configured rule " + matchCount + " times.";
    }

    private String growthDescription(int matchCount) {
        return matchCount == 0
                ? "The selected window did not show enough growth to match the rule."
                : "The later half of the selected window exceeded the configured growth rule.";
    }

    private String longRunDescription(int matchCount) {
        return matchCount == 0
                ? "The selected window did not contain a sustained high run."
                : "Telemetry stayed above the high-run threshold for " + matchCount + " samples.";
    }

    private String title(String ruleType) {
        return switch (normalize(ruleType)) {
            case "POWER_SPIKE" -> "Power spike detected";
            case "CONSUMPTION_GROWTH" -> "Consumption growth detected";
            case "LONG_CYCLE" -> "Long cycle detected";
            case "OFFLINE" -> "Telemetry stopped";
            default -> "Rule matched";
        };
    }

    private String formatMetric(String metric) {
        return metric == null ? "Telemetry" : metric;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
