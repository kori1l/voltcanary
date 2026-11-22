package com.voltcanary.analytics.service;

import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.domain.MaintenanceMlBehavior;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.PredictionMode;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.dto.MlSettingsResponse;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlExcludedInterval;
import com.voltcanary.analytics.ml.MlInferenceRequest;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsPrediction {
    public record PreparedPrediction(PredictionResult primary, List<PredictionResult> all) { }
    private final MlClient mlClient;
    private final MlModelRegistryService mlModelRegistryService;
    private final AnalyticsPredictionTraceService predictionTraceService;
    private final AnalyticsMlRuntimeDecisionService mlRuntimeDecisionService;
    private final AnalyticsBaselineFeatureService baselineFeatureService;

    public PreparedPrediction prepare(
            UUID deviceId,
            TelemetryChartResponse chart,
            BaselineStats baseline,
            RiskScore risk,
            Instant now,
            List<MaintenanceWindowReadModel> maintenanceWindows,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            UUID accountId,
            String deviceType,
            MlSettingsResponse settings,
            MlResolvedDeploymentPolicyResponse policy) {
        List<PredictionResult> computedPredictions = new ArrayList<>();
        String metric = chart == null ? "" : chart.metric();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("metric", metric);
        metrics.put("sampleCount", baseline.sampleCount());
        metrics.put("mean", baseline.mean());
        metrics.put("median", baseline.median());
        metrics.put("p90", baseline.p90());
        metrics.put("min", baseline.min());
        metrics.put("max", baseline.max());
        metrics.put("riskScore", risk.getScore());
        metrics.put("latestValue", baselineFeatureService.latestValue(chart));
        metrics.put("deviceType", blankToEmpty(deviceType));
        if (!metric.isBlank()) {
            metrics.put(metric, baselineFeatureService.latestValue(chart));
        }
        metrics.put("maintenanceExcludedWindowIds", maintenanceWindows.stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                .map(MaintenanceWindowReadModel::getMaintenanceWindowId)
                .map(UUID::toString)
                .toList());
        Map<String, Object> baselineContext = Map.of(
                "metric", metric,
                "sampleCount", baseline.sampleCount(),
                "mean", baseline.mean(),
                "median", baseline.median(),
                "p90", baseline.p90(),
                "min", baseline.min(),
                "max", baseline.max());
        Map<String, Object> recentWindow = Map.of(
                "sampleCount", baseline.sampleCount(),
                "excludedSampleCount", maintenanceWindows.stream()
                        .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                        .count(),
                "windowStart", windowStart.toString(),
                "windowEnd", windowEnd.toString());
        boolean allowBaseline = settings.predictionMode() == PredictionMode.MODEL_OR_BASELINE_HEURISTIC
                && policy.fallbackEnabled();
        Map<String, Object> modelPreference = new HashMap<>();
        modelPreference.put("allowBaselineHeuristic", allowBaseline);
        modelPreference.put("predictionMode", settings.predictionMode().name());
        modelPreference.put("deploymentMode", policy.mode().name());
        modelPreference.put("policySource", policy.policySource() == null ? "" : policy.policySource().name());
        modelPreference.put("policyId", policy.policyId() == null ? "" : policy.policyId().toString());
        modelPreference.put("modelVersionId", policy.modelVersionId() == null ? "" : policy.modelVersionId().toString());
        modelPreference.put("resolvedDeviceType", blankToEmpty(deviceType));
        modelPreference.put("canaryPercent", policy.canaryPercent() == null ? "" : policy.canaryPercent());
        boolean canaryIncluded = policy.mode() != MlDeploymentMode.CANARY
                || mlRuntimeDecisionService.canaryIncluded(accountId, deviceId, policy.modelVersionId(), policy.canaryPercent());
        modelPreference.put("canaryIncluded", canaryIncluded);
        String compatibilityFallbackReason = "";
        if (accountId != null && policy.modelVersionId() != null) {
            MlModelVersionDto model = mlModelRegistryService.getModel(accountId, policy.modelVersionId());
            Map<String, Object> modelMetrics = model.evaluationMetricsJson() == null || model.evaluationMetricsJson().isBlank()
                    ? Map.of()
                    : predictionTraceService.readJson(model.evaluationMetricsJson());
            modelPreference.put("requiredFeatureSchemaHash", model.featureSchemaHash() == null ? "" : model.featureSchemaHash());
            modelPreference.put("requiredFeatureContractVersion", string(modelMetrics.get("requiredFeatureContractVersion")).isBlank()
                    ? FeatureVectorContract.CONTRACT_VERSION
                    : string(modelMetrics.get("requiredFeatureContractVersion")));
            modelPreference.put("requiredFeatureContractMetadataHash", string(modelMetrics.get("requiredFeatureContractMetadataHash")));
            modelPreference.put("requiredFeatureSetVersion", string(modelMetrics.get("featureSetVersion")));
            modelPreference.put("requiredFeatureGeneratorVersion", string(modelMetrics.get("featureGeneratorVersion")));
            modelPreference.put("requiredFeatureTemplateId", string(modelMetrics.get("featureTemplateId")));
            modelPreference.put("requiredFeatureTemplateVersion", string(modelMetrics.get("featureTemplateVersion")));
            modelPreference.put("requiredHorizonDays", modelMetrics.get("featureSnapshotHorizonDays") == null ? "" : modelMetrics.get("featureSnapshotHorizonDays"));
            modelPreference.put("requiredFeatureConfigHash", string(modelMetrics.get("requiredFeatureConfigHash")));
            modelPreference.put("modelFamily", model.family() == null ? "" : model.family().name());
            modelPreference.put("modelVersion", Integer.toString(model.version()));
            modelPreference.put("modelStatus", model.status() == null ? "" : model.status().name());
            modelPreference.put("modelType", mlRuntimeDecisionService.runtimeModelType(model));
            modelPreference.put("artifactUri", model.artifactUri() == null ? "" : model.artifactUri());
            modelPreference.put("manifestUri", model.artifactManifestUri() == null ? "" : model.artifactManifestUri());
            modelPreference.put("artifactHash", model.artifactHash() == null ? "" : model.artifactHash());
            modelPreference.put("artifactSizeBytes", model.artifactSizeBytes() == null ? 0L : model.artifactSizeBytes());
            compatibilityFallbackReason = mlRuntimeDecisionService.compatibilityFallbackReason(model, deviceType, metric);
        }
        boolean maintenanceOverlap = maintenanceWindows.stream().anyMatch(MaintenanceWindowReadModel::isExcludeFromMl);
        boolean disabled = !settings.mlEnabled() || settings.predictionMode() == PredictionMode.RULE_BASED_ONLY;
        boolean suppressMl = disabled || (maintenanceOverlap
                && settings.maintenanceBehavior() == MaintenanceMlBehavior.SUPPRESS_ML)
                || !canaryIncluded
                || !compatibilityFallbackReason.isBlank();
        List<MlExcludedInterval> excludedIntervals = maintenanceWindows.stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                .map(window -> new MlExcludedInterval(
                        window.getMaintenanceWindowId(),
                        window.getStartAt(),
                        window.getEndAt(),
                        "MAINTENANCE"))
                .toList();
        MlInferenceRequest inferenceRequest = new MlInferenceRequest(
                deviceId,
                now,
                metrics,
                excludedIntervals,
                baselineContext,
                recentWindow,
                modelPreference);
        MlInferenceResponse response = suppressMl ? null : mlClient.requestPrediction(
                deviceId,
                now,
                metrics,
                excludedIntervals,
                baselineContext,
                recentWindow,
                modelPreference);
        if (!suppressMl && policy.mode() == MlDeploymentMode.SHADOW && response != null) {
            computedPredictions.add(predictionTraceService.buildPredictionResult(
                    deviceId,
                    response,
                    now,
                    inputHash,
                    "SHADOW",
                    policy,
                    true,
                    "",
                    deviceType,
                    canaryIncluded));
            response = policy.fallbackEnabled() ? null : response;
        }
        String fallbackReason = disabled
                ? "ML_DISABLED"
                : !canaryIncluded
                ? "CANARY_NOT_INCLUDED"
                : !compatibilityFallbackReason.isBlank()
                ? compatibilityFallbackReason
                : suppressMl
                ? "MAINTENANCE_SUPPRESSED"
                : response == null
                ? (policy.fallbackReason() == null ? "ML_UNAVAILABLE_OR_BASELINE_FALLBACK" : policy.fallbackReason())
                : "";
        String status = disabled
                ? "DISABLED"
                : !canaryIncluded
                ? "CANARY_NOT_INCLUDED"
                : !compatibilityFallbackReason.isBlank()
                ? "INCOMPATIBLE_MODEL"
                : suppressMl
                ? "SUPPRESSED_MAINTENANCE"
                : response == null
                ? "UNAVAILABLE"
                : "AVAILABLE";
        String predictionStatus = response == null || response.predictionStatus() == null
                ? status
                : response.predictionStatus();
        String responseInputHash = response == null || response.inputHash() == null || response.inputHash().isBlank()
                ? inputHash
                : response.inputHash();
        PredictionResult result = predictionTraceService.buildPredictionResult(
                deviceId,
                response,
                now,
                responseInputHash,
                predictionStatus,
                policy,
                false,
                fallbackReason,
                deviceType,
                canaryIncluded);
        result.setStatus(predictionStatus);
        Map<String, Object> payload = predictionTraceService.predictionPayload(
                response,
                policy,
                predictionStatus,
                fallbackReason,
                false,
                responseInputHash,
                deviceType,
                canaryIncluded);
        payload.put("availabilityStatus", status);
        payload.put("generatedFeatureContract", inferenceRequest.featureContract());
        payload.put("featureGenerationStatus", inferenceRequest.featureGenerationStatus());
        payload.put("featureGenerationErrors", inferenceRequest.featureGenerationErrors());
        payload.put("requiredFeatureSchemaHash", string(modelPreference.get("requiredFeatureSchemaHash")));
        payload.put("requiredFeatureContractMetadataHash", string(modelPreference.get("requiredFeatureContractMetadataHash")));
        payload.put("generatedFeatureContractMetadataHash", string(inferenceRequest.featureContract().get("contractMetadataHash")));
        payload.put("confidence", response == null
                ? 0
                : (maintenanceOverlap && settings.maintenanceBehavior() == MaintenanceMlBehavior.REDUCE_CONFIDENCE
                        ? response.confidence() * settings.confidenceWarningThreshold()
                        : response.confidence()));
        payload.put("reason", disabled ? "disabled" : suppressMl ? "maintenance" : "");
        payload.put("status", status);
        result.setPredictionJson(predictionTraceService.writeJson(payload));
        computedPredictions.add(result);
        return new PreparedPrediction(result, List.copyOf(computedPredictions));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
