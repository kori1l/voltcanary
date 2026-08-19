package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsPredictionTraceService {

    private final ObjectMapper objectMapper;

    public AnalyticsPredictionTraceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PredictionResult buildPredictionResult(
            UUID deviceId,
            MlInferenceResponse response,
            Instant now,
            String inputHash,
            String predictionStatus,
            MlResolvedDeploymentPolicyResponse policy,
            boolean shadowPrediction,
            String fallbackReason,
            String deviceType,
            boolean canaryIncluded) {
        PredictionResult result = new PredictionResult();
        result.setId(UUID.randomUUID());
        result.setDeviceId(deviceId);
        result.setModelId(policy.modelVersionId());
        result.setPolicySource(policy.policySource() == null ? null : policy.policySource().name());
        result.setFeatureSchemaHash("");
        result.setFallbackReason(fallbackReason);
        result.setShadowPrediction(shadowPrediction);
        result.setModelName(response == null ? "ml-unavailable" : response.modelName());
        result.setModelVersion(response == null ? "unknown" : response.modelVersion());
        result.setAnomalyScore(response == null ? 0 : response.anomalyScore());
        result.setDegradationScore(response == null ? 0 : response.degradationScore());
        result.setConfidence(response == null ? 0 : response.confidence());
        result.setRiskLevel(response == null ? "" : response.riskLevel());
        result.setStatus(predictionStatus);
        result.setInputHash(inputHash);
        result.setPredictionJson(writeJson(predictionPayload(
                response,
                policy,
                predictionStatus,
                fallbackReason,
                shadowPrediction,
                inputHash,
                deviceType,
                canaryIncluded)));
        result.setCreatedAt(now);
        return result;
    }

    public Map<String, Object> predictionPayload(
            MlInferenceResponse response,
            MlResolvedDeploymentPolicyResponse policy,
            String predictionStatus,
            String fallbackReason,
            boolean shadowPrediction,
            String inputHash,
            String deviceType,
            boolean canaryIncluded) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("predictionStatus", predictionStatus);
        payload.put("modelId", policy.modelVersionId() == null ? "" : policy.modelVersionId().toString());
        payload.put("responseModelVersionId", response == null || response.modelVersionId() == null ? "" : response.modelVersionId());
        payload.put("modelFamily", response == null || response.modelFamily() == null ? "" : response.modelFamily());
        payload.put("policySource", policy.policySource() == null ? "" : policy.policySource().name());
        payload.put("policyId", policy.policyId() == null ? "" : policy.policyId().toString());
        payload.put("deploymentMode", policy.mode().name());
        payload.put("resolvedDeviceType", blankToEmpty(deviceType));
        payload.put("canaryPercent", policy.canaryPercent());
        payload.put("canaryIncluded", canaryIncluded);
        payload.put("predictionPath", shadowPrediction ? "SHADOW" : fallbackReason == null || fallbackReason.isBlank() ? policy.mode().name() : "FALLBACK");
        payload.put("fallbackReason", fallbackReason);
        payload.put("shadowPrediction", shadowPrediction);
        payload.put("modelName", response == null ? "ml-unavailable" : response.modelName());
        payload.put("modelVersion", response == null ? "unknown" : response.modelVersion());
        payload.put("modelType", response == null || response.modelType() == null ? "" : response.modelType());
        payload.put("modelStatus", response == null || response.modelStatus() == null ? "" : response.modelStatus());
        payload.put("anomalyScore", response == null ? 0 : response.anomalyScore());
        payload.put("degradationScore", response == null ? 0 : response.degradationScore());
        payload.put("failureRiskScore", response == null ? null : response.failureRiskScore());
        payload.put("failureHorizonDays", response == null ? null : response.failureHorizonDays());
        payload.put("failureRiskBand", response == null ? null : response.failureRiskBand());
        payload.put("riskLevel", response == null ? "" : response.riskLevel());
        payload.put("confidence", response == null ? 0 : response.confidence());
        payload.put("riskDrivers", response == null || response.riskDrivers() == null ? List.of() : response.riskDrivers());
        payload.put("labelCoverage", response == null ? null : response.labelCoverage());
        payload.put("trainingDataWindow", response == null || response.trainingDataWindow() == null ? Map.of() : response.trainingDataWindow());
        payload.put("signals", response == null || response.signals() == null ? List.of() : response.signals());
        payload.put("recommendedActions", response == null || response.recommendedActions() == null ? List.of() : response.recommendedActions());
        payload.put("inputHash", inputHash);
        payload.put("explanations", response == null || response.explanations() == null ? List.of() : response.explanations());
        return payload;
    }

    public String inputHash(
            UUID deviceId,
            Instant windowStart,
            Instant windowEnd,
            List<TelemetryChartResponse> charts,
            List<MaintenanceWindowReadModel> maintenanceWindows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((deviceId + "|" + windowStart + "|" + windowEnd).getBytes(StandardCharsets.UTF_8));
            charts.stream()
                    .filter(chart -> chart != null)
                    .sorted(Comparator.comparing(TelemetryChartResponse::metric))
                    .forEach(chart -> {
                        digest.update(("|" + chart.metric()).getBytes(StandardCharsets.UTF_8));
                        chart.points().stream()
                                .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                                .forEach(point -> digest.update(
                                        ("|" + point.timestamp() + "=" + point.value()).getBytes(StandardCharsets.UTF_8)));
                    });
            maintenanceWindows.stream()
                    .sorted(Comparator.comparing(MaintenanceWindowReadModel::getMaintenanceWindowId))
                    .forEach(window -> digest.update(
                            ("|maintenance:" + window.getMaintenanceWindowId() + ":" + window.getVersion()
                                    + ":" + window.isExcludeFromAnalytics() + ":" + window.isExcludeFromMl())
                                    .getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write analytics JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read analytics JSON", e);
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
