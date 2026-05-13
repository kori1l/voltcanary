package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.anomaly.Anomaly;
import com.voltcanary.analytics.baseline.DeviceBaseline;
import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.prediction.PredictionResult;
import com.voltcanary.analytics.producer.AnalyticsEventPublisher;
import com.voltcanary.analytics.recommendation.Recommendation;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.repository.RiskScoreRepository;
import com.voltcanary.analytics.risk.RiskScore;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsRecalculationService {

    private static final String METRIC = "activePowerW";
    private static final String PERIOD = "last_24h";

    private final TelemetryClient telemetryClient;
    private final DeviceBaselineRepository baselineRepository;
    private final AnomalyRepository anomalyRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RecommendationRepository recommendationRepository;
    private final PredictionResultRepository predictionResultRepository;
    private final ObjectMapper objectMapper;
    private final AnalyticsEventPublisher eventPublisher;
    private final MlClient mlClient;

    public AnalyticsRecalculationService(
            TelemetryClient telemetryClient,
            DeviceBaselineRepository baselineRepository,
            AnomalyRepository anomalyRepository,
            RiskScoreRepository riskScoreRepository,
            RecommendationRepository recommendationRepository,
            PredictionResultRepository predictionResultRepository,
            ObjectMapper objectMapper,
            AnalyticsEventPublisher eventPublisher,
            MlClient mlClient) {
        this.telemetryClient = telemetryClient;
        this.baselineRepository = baselineRepository;
        this.anomalyRepository = anomalyRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.recommendationRepository = recommendationRepository;
        this.predictionResultRepository = predictionResultRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.mlClient = mlClient;
    }

    @Transactional
    public RecalculationResult recalculate(UUID deviceId) {
        Instant now = Instant.now();
        TelemetryChartResponse chart = telemetryClient.readings(deviceId, METRIC, now.minus(24, ChronoUnit.HOURS), now);
        List<Double> values = chart == null ? List.of() : chart.points().stream()
                .map(TelemetryChartResponse.Point::value)
                .sorted()
                .toList();

        BaselineStats baseline = baseline(values);
        DeviceBaseline savedBaseline = saveBaseline(deviceId, baseline, now);

        anomalyRepository.deleteByDeviceId(deviceId);
        recommendationRepository.deleteByDeviceId(deviceId);

        List<Anomaly> anomalies = anomalyRepository.saveAll(detectAnomalies(deviceId, chart, baseline, now));
        RiskScore risk = riskScoreRepository.save(risk(deviceId, anomalies, values, now));
        PredictionResult prediction = predictionResultRepository.save(prediction(deviceId, chart, baseline, risk, now));
        List<Recommendation> recommendations = recommendationRepository.saveAll(recommendations(deviceId, anomalies, now));
        anomalies.forEach(eventPublisher::publishAnomaly);
        eventPublisher.publishRiskScore(risk);

        return new RecalculationResult(savedBaseline, anomalies, risk, recommendations, prediction);
    }

    @Transactional(readOnly = true)
    public Optional<BaselineResponse> baseline(UUID deviceId) {
        return baselineRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
                .map(this::toBaselineResponse);
    }

    @Transactional(readOnly = true)
    public List<AnomalyResponse> anomalies(UUID deviceId) {
        return anomalyRepository.findTop100ByDeviceIdOrderByDetectedAtDesc(deviceId).stream()
                .map(this::toAnomalyResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<RiskResponse> risk(UUID deviceId) {
        return riskScoreRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
                .map(score -> toRiskResponse(score, anomalies(deviceId)));
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> recommendations(UUID deviceId) {
        return recommendationRepository.findTop100ByDeviceIdOrderByCreatedAtDesc(deviceId).stream()
                .map(this::toRecommendationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PredictionResponse> latestPrediction(UUID deviceId) {
        return predictionResultRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId)
                .map(this::toPredictionResponse);
    }

    private DeviceBaseline saveBaseline(UUID deviceId, BaselineStats baseline, Instant now) {
        DeviceBaseline entity = new DeviceBaseline();
        entity.setId(UUID.randomUUID());
        entity.setDeviceId(deviceId);
        entity.setCalculatedAt(now);
        entity.setBaselineJson(writeJson(Map.of(
                "metric", METRIC,
                "period", PERIOD,
                "mean", baseline.mean(),
                "median", baseline.median(),
                "p90", baseline.p90(),
                "min", baseline.min(),
                "max", baseline.max(),
                "sampleCount", baseline.sampleCount())));
        return baselineRepository.save(entity);
    }

    private List<Anomaly> detectAnomalies(UUID deviceId, TelemetryChartResponse chart, BaselineStats baseline, Instant now) {
        if (chart == null || chart.points().isEmpty() || baseline.sampleCount() == 0) {
            return List.of();
        }

        List<Anomaly> anomalies = new ArrayList<>();
        double spikeThreshold = Math.max(baseline.median() * 1.8, baseline.mean() * 1.2);
        if (baseline.max() > spikeThreshold && baseline.max() > baseline.mean()) {
            anomalies.add(anomaly(
                    deviceId,
                    "POWER_SPIKE",
                    "MEDIUM",
                    30,
                    "A power spike was detected above the normal range. Check device load and power conditions.",
                    now));
        }

        List<TelemetryChartResponse.Point> points = chart.points().stream()
                .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                .toList();
        if (points.size() >= 4) {
            int midpoint = points.size() / 2;
            double firstAverage = average(points.subList(0, midpoint));
            double secondAverage = average(points.subList(midpoint, points.size()));
            if (firstAverage > 0 && secondAverage > firstAverage * 1.15) {
                anomalies.add(anomaly(
                        deviceId,
                        "CONSUMPTION_GROWTH",
                        "MEDIUM",
                        30,
                        "Average consumption increased compared to baseline. Check operating mode and external conditions. If the anomaly repeats, maintenance is recommended.",
                        now));
            }
        }

        long highRun = points.stream().filter(point -> point.value() > baseline.p90()).count();
        if (points.size() >= 8 && highRun >= Math.max(4, points.size() / 2)) {
            anomalies.add(anomaly(
                    deviceId,
                    "LONG_CYCLE",
                    "LOW",
                    20,
                    "Power stayed above the normal range for an extended portion of the window.",
                    now));
        }

        return anomalies;
    }

    private RiskScore risk(UUID deviceId, List<Anomaly> anomalies, List<Double> values, Instant now) {
        int score = 0;
        for (Anomaly anomaly : anomalies) {
            score += switch (anomaly.getType()) {
                case "POWER_SPIKE", "CONSUMPTION_GROWTH" -> 30;
                case "LONG_CYCLE" -> 20;
                default -> 0;
            };
        }
        if (values.size() < 3) {
            score += 10;
        }
        if (values.isEmpty()) {
            score += 10;
        }
        score = Math.min(100, score);

        RiskScore riskScore = new RiskScore();
        riskScore.setId(UUID.randomUUID());
        riskScore.setDeviceId(deviceId);
        riskScore.setScore(score);
        riskScore.setLevel(level(score));
        riskScore.setCalculatedAt(now);
        return riskScore;
    }

    private List<Recommendation> recommendations(UUID deviceId, List<Anomaly> anomalies, Instant now) {
        List<Recommendation> recommendations = new ArrayList<>();
        for (Anomaly anomaly : anomalies) {
            if ("CONSUMPTION_GROWTH".equals(anomaly.getType())) {
                recommendations.add(recommendation(
                        deviceId,
                        "Check consumption growth",
                        "Average consumption increased compared to baseline. Check operating mode and external conditions. If the anomaly repeats, maintenance is recommended.",
                        "MEDIUM",
                        now));
            }
            if ("POWER_SPIKE".equals(anomaly.getType())) {
                recommendations.add(recommendation(
                        deviceId,
                        "Check power conditions",
                        "A power spike was detected above the normal range. Check device load and power conditions.",
                        "MEDIUM",
                        now));
            }
        }
        return recommendations;
    }

    private PredictionResult prediction(UUID deviceId, TelemetryChartResponse chart, BaselineStats baseline, RiskScore risk, Instant now) {
        Map<String, Object> metrics = Map.of(
                "metric", METRIC,
                "sampleCount", baseline.sampleCount(),
                "mean", baseline.mean(),
                "median", baseline.median(),
                "p90", baseline.p90(),
                "min", baseline.min(),
                "max", baseline.max(),
                "riskScore", risk.getScore(),
                "latestValue", latestValue(chart));
        MlInferenceResponse response = mlClient.requestPrediction(deviceId, now, metrics);
        PredictionResult result = new PredictionResult();
        result.setId(UUID.randomUUID());
        result.setDeviceId(deviceId);
        result.setModelName(response == null ? "unknown" : response.modelVersion());
        result.setPredictionJson(writeJson(Map.of(
                "modelVersion", response == null ? "unknown" : response.modelVersion(),
                "anomalyScore", response == null ? 0 : response.anomalyScore(),
                "degradationScore", response == null ? 0 : response.degradationScore(),
                "riskLevel", response == null ? "" : response.riskLevel(),
                "confidence", response == null ? 0 : response.confidence(),
                "explanations", response == null || response.explanations() == null ? List.of() : response.explanations())));
        result.setCreatedAt(now);
        return result;
    }

    private BaselineStats baseline(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) {
            return new BaselineStats(0, 0, 0, 0, 0, 0);
        }
        double sum = sortedValues.stream().mapToDouble(Double::doubleValue).sum();
        return new BaselineStats(
                sum / sortedValues.size(),
                percentile(sortedValues, 0.5),
                percentile(sortedValues, 0.9),
                sortedValues.get(0),
                sortedValues.get(sortedValues.size() - 1),
                sortedValues.size());
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private double average(List<TelemetryChartResponse.Point> points) {
        return points.stream().mapToDouble(TelemetryChartResponse.Point::value).average().orElse(0);
    }

    private Anomaly anomaly(UUID deviceId, String type, String severity, double score, String description, Instant now) {
        Anomaly anomaly = new Anomaly();
        anomaly.setId(UUID.randomUUID());
        anomaly.setDeviceId(deviceId);
        anomaly.setType(type);
        anomaly.setSeverity(severity);
        anomaly.setScore(score);
        anomaly.setDescription(description);
        anomaly.setDetectedAt(now);
        return anomaly;
    }

    private Recommendation recommendation(UUID deviceId, String title, String description, String priority, Instant now) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setDeviceId(deviceId);
        recommendation.setTitle(title);
        recommendation.setDescription(description);
        recommendation.setPriority(priority);
        recommendation.setCreatedAt(now);
        return recommendation;
    }

    private BaselineResponse toBaselineResponse(DeviceBaseline baseline) {
        Map<String, Object> values = readJson(baseline.getBaselineJson());
        return new BaselineResponse(
                baseline.getDeviceId(),
                string(values.get("metric")),
                string(values.get("period")),
                number(values.get("mean")),
                number(values.get("median")),
                number(values.get("p90")),
                number(values.get("min")),
                number(values.get("max")),
                (int) number(values.get("sampleCount")),
                baseline.getCalculatedAt());
    }

    private AnomalyResponse toAnomalyResponse(Anomaly anomaly) {
        return new AnomalyResponse(
                anomaly.getId(),
                anomaly.getDeviceId(),
                anomaly.getType(),
                anomaly.getSeverity(),
                anomaly.getScore(),
                anomaly.getDescription(),
                anomaly.getDetectedAt());
    }

    private RiskResponse toRiskResponse(RiskScore riskScore, List<AnomalyResponse> anomalies) {
        return new RiskResponse(
                riskScore.getDeviceId(),
                riskScore.getScore(),
                riskScore.getLevel(),
                anomalies.stream().map(AnomalyResponse::description).toList(),
                riskScore.getCalculatedAt());
    }

    private RecommendationResponse toRecommendationResponse(Recommendation recommendation) {
        return new RecommendationResponse(
                recommendation.getId(),
                recommendation.getDeviceId(),
                recommendation.getTitle(),
                recommendation.getDescription(),
                recommendation.getPriority(),
                recommendation.getCreatedAt());
    }

    private PredictionResponse toPredictionResponse(PredictionResult prediction) {
        Map<String, Object> values = readJson(prediction.getPredictionJson());
        Object explanations = values.get("explanations");
        return new PredictionResponse(
                prediction.getDeviceId(),
                string(values.get("modelVersion")),
                number(values.get("anomalyScore")),
                number(values.get("degradationScore")),
                number(values.get("confidence")),
                explanations instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(),
                prediction.getCreatedAt());
    }

    private String level(int score) {
        if (score <= 30) {
            return "LOW";
        }
        if (score <= 60) {
            return "MEDIUM";
        }
        if (score <= 80) {
            return "HIGH";
        }
        return "CRITICAL";
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write analytics JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read analytics JSON", e);
        }
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double latestValue(TelemetryChartResponse chart) {
        if (chart == null || chart.points().isEmpty()) {
            return 0;
        }
        return chart.points().stream()
                .max(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                .map(TelemetryChartResponse.Point::value)
                .orElse(0.0);
    }

    public record RecalculationResult(
            DeviceBaseline baseline,
            List<Anomaly> anomalies,
            RiskScore riskScore,
            List<Recommendation> recommendations,
            PredictionResult prediction) {
    }

    private record BaselineStats(
            double mean,
            double median,
            double p90,
            double min,
            double max,
            int sampleCount) {
    }
}
