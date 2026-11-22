package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.domain.FeatureSet;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.Recommendation;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.FeatureSetResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.FeatureSetRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.repository.RiskScoreRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalyticsReadService {

    private final DeviceBaselineRepository baselineRepository;
    private final AnomalyRepository anomalyRepository;
    private final FeatureSetRepository featureSetRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RecommendationRepository recommendationRepository;
    private final PredictionResultRepository predictionResultRepository;
    private final AnalyticsPredictionTraceService predictionTraceService;

    @Transactional(readOnly = true)
    public Optional<BaselineResponse> baseline(UUID deviceId) {
        return baselineRepository
            .findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
            .map(this::toBaselineResponse);
    }

    @Transactional(readOnly = true)
    public List<AnomalyResponse> anomalies(UUID deviceId) {
        return anomalyRepository
            .findTop100ByDeviceIdAndStatusOrderByDetectedAtDesc(deviceId, "ACTIVE")
            .stream()
            .map(this::toAnomalyResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<FeatureSetResponse> features(UUID deviceId) {
        return featureSetRepository
            .findTop100ByDeviceIdOrderByWindowEndDesc(deviceId)
            .stream()
            .map(this::toFeatureSetResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<RiskResponse> risk(UUID deviceId) {
        return riskScoreRepository
            .findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
            .map(score -> toRiskResponse(score, anomalies(deviceId)));
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> recommendations(UUID deviceId) {
        return recommendationRepository
            .findTop100ByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "ACTIVE")
            .stream()
            .map(this::toRecommendationResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PredictionResponse> latestPrediction(UUID deviceId) {
        return predictionResultRepository
            .findFirstByDeviceIdAndShadowPredictionFalseOrderByCreatedAtDesc(deviceId)
            .map(this::toPredictionResponse);
    }

    private BaselineResponse toBaselineResponse(DeviceBaseline baseline) {
        Map<String, Object> values = readJson(baseline.getBaselineJson());
        return new BaselineResponse(
            baseline.getDeviceId(),
            baseline.getMetric() == null ? string(values.get("metric")) : baseline.getMetric(),
            baseline.getPeriod() == null ? string(values.get("period")) : baseline.getPeriod(),
            baseline.getMean() == null ? number(values.get("mean")) : baseline.getMean(),
            baseline.getMedian() == null ? number(values.get("median")) : baseline.getMedian(),
            baseline.getP90() == null ? number(values.get("p90")) : baseline.getP90(),
            baseline.getMin() == null ? number(values.get("min")) : baseline.getMin(),
            baseline.getMax() == null ? number(values.get("max")) : baseline.getMax(),
            baseline.getSampleCount() == null
                ? (int) number(values.get("sampleCount"))
                : baseline.getSampleCount(),
            baseline.getCalculatedAt()
        );
    }

    private AnomalyResponse toAnomalyResponse(Anomaly anomaly) {
        return new AnomalyResponse(
            anomaly.getId(),
            anomaly.getDeviceId(),
            anomaly.getType(),
            anomaly.getSeverity(),
            anomaly.getScore(),
            anomaly.getDescription(),
            anomaly.getDetectedAt()
        );
    }

    private RiskResponse toRiskResponse(RiskScore riskScore, List<AnomalyResponse> anomalies) {
        return new RiskResponse(
            riskScore.getDeviceId(),
            riskScore.getScore(),
            riskScore.getLevel(),
            anomalies.stream().map(AnomalyResponse::description).toList(),
            riskScore.getCalculatedAt()
        );
    }

    private FeatureSetResponse toFeatureSetResponse(FeatureSet featureSet) {
        return new FeatureSetResponse(
            featureSet.getId(),
            featureSet.getDeviceId(),
            featureSet.getWindowStart(),
            featureSet.getWindowEnd(),
            readJson(featureSet.getFeaturesJson())
        );
    }

    private RecommendationResponse toRecommendationResponse(Recommendation recommendation) {
        return new RecommendationResponse(
            recommendation.getId(),
            recommendation.getDeviceId(),
            recommendation.getTitle(),
            recommendation.getDescription(),
            recommendation.getPriority(),
            recommendation.getCreatedAt()
        );
    }

    private PredictionResponse toPredictionResponse(PredictionResult prediction) {
        Map<String, Object> values = readJson(prediction.getPredictionJson());
        Object explanations = values.get("explanations");
        Object signals = values.get("signals");
        Object riskDrivers = values.get("riskDrivers");
        Object recommendedActions = values.get("recommendedActions");
        Object trainingDataWindow = values.get("trainingDataWindow");
        return new PredictionResponse(
            prediction.getDeviceId(),
            prediction.getModelId() == null
                ? string(values.get("responseModelVersionId"))
                : prediction.getModelId().toString(),
            string(values.get("modelFamily")),
            prediction.getModelName() == null
                ? string(values.get("modelName"))
                : prediction.getModelName(),
            prediction.getModelVersion() == null
                ? string(values.get("modelVersion"))
                : prediction.getModelVersion(),
            string(values.get("modelType")),
            string(values.get("modelStatus")),
            prediction.getStatus() == null
                ? string(values.get("predictionStatus"))
                : prediction.getStatus(),
            prediction.getPolicySource() == null
                ? string(values.get("policySource"))
                : prediction.getPolicySource(),
            string(values.get("deploymentMode")),
            string(values.get("resolvedDeviceType")),
            nullableInteger(values.get("canaryPercent")),
            nullableBoolean(values.get("canaryIncluded")),
            string(values.get("predictionPath")),
            prediction.getFallbackReason() == null
                ? string(values.get("fallbackReason"))
                : prediction.getFallbackReason(),
            prediction.isShadowPrediction(),
            prediction.getAnomalyScore() == null
                ? number(values.get("anomalyScore"))
                : prediction.getAnomalyScore(),
            prediction.getDegradationScore() == null
                ? number(values.get("degradationScore"))
                : prediction.getDegradationScore(),
            nullableNumber(values.get("failureRiskScore")),
            nullableInteger(values.get("failureHorizonDays")),
            nullableString(values.get("failureRiskBand")),
            prediction.getConfidence() == null
                ? number(values.get("confidence"))
                : prediction.getConfidence(),
            prediction.getInputHash() == null
                ? string(values.get("inputHash"))
                : prediction.getInputHash(),
            maps(riskDrivers),
            nullableNumber(values.get("labelCoverage")),
            trainingDataWindow instanceof Map<?, ?> window
                ? (Map<String, Object>) window
                : Map.of(),
            signals instanceof List<?> signalList
                ? signalList
                      .stream()
                      .filter(Map.class::isInstance)
                      .map(item -> (Map<String, Object>) item)
                      .toList()
                : List.of(),
            explanations instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of(),
            recommendedActions instanceof List<?> actionList
                ? actionList.stream().map(Object::toString).toList()
                : List.of(),
            prediction.getCreatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list
            ? list
                  .stream()
                  .filter(Map.class::isInstance)
                  .map(item -> (Map<String, Object>) item)
                  .toList()
            : List.of();
    }

    private Double nullableNumber(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Boolean nullableBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private String nullableString(Object value) {
        String result = string(value);
        return result.isBlank() ? null : result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        return predictionTraceService.readJson(json);
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
