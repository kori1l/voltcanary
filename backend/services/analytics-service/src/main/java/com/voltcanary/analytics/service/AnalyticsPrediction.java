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
import com.voltcanary.analytics.ml.MlInferenceBaseline;
import com.voltcanary.analytics.ml.MlInferenceModelPreference;
import com.voltcanary.analytics.ml.MlInferenceRecentWindow;
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

    public record PreparedPrediction(PredictionResult primary, List<PredictionResult> all) {}

    private final MlClient mlClient;
    private final MlModelRegistryService mlModelRegistryService;
    private final AnalyticsPredictionTraceService predictionTraceService;
    private final AnalyticsMlRuntimeDecisionService mlRuntimeDecisionService;
    private final AnalyticsBaselineFeatureService baselineFeatureService;

    public PreparedPrediction prepare(
        AnalyticsInputReader.Input input,
        AnalyticsComputation.Computed calculated
    ) {
        var context = input.context();
        UUID deviceId = input.device();
        TelemetryChartResponse chart = calculated.primaryChart();
        BaselineStats baseline = calculated.primaryBaseline();
        RiskScore risk = calculated.risk();
        Instant now = input.now();
        List<MaintenanceWindowReadModel> maintenanceWindows = context.windows();
        Instant windowStart = input.from();
        Instant windowEnd = input.to();
        String inputHash = input.inputHash();
        UUID accountId = context.accountId();
        String deviceType = context.deviceType();
        MlSettingsResponse settings = context.settings();
        MlResolvedDeploymentPolicyResponse policy = context.policy();
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
        metrics.put(
            "maintenanceExcludedWindowIds",
            maintenanceWindows
                .stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                .map(MaintenanceWindowReadModel::getMaintenanceWindowId)
                .map(UUID::toString)
                .toList()
        );
        var baselineContext = new MlInferenceBaseline(
            metric,
            baseline.sampleCount(),
            baseline.mean(),
            baseline.median(),
            baseline.p90(),
            baseline.min(),
            baseline.max()
        );
        var recentWindow = new MlInferenceRecentWindow(
            baseline.sampleCount(),
            maintenanceWindows.stream().filter(MaintenanceWindowReadModel::isExcludeFromMl).count(),
            windowStart.toString(),
            windowEnd.toString()
        );
        boolean allowBaseline =
            settings.predictionMode() == PredictionMode.MODEL_OR_BASELINE_HEURISTIC &&
            policy.fallbackEnabled();
        var preferenceBuilder = MlInferenceModelPreference.builder();
        preferenceBuilder.allowBaselineHeuristic(allowBaseline);
        preferenceBuilder.predictionMode(settings.predictionMode().name());
        preferenceBuilder.deploymentMode(policy.mode().name());
        preferenceBuilder.policySource(
            policy.policySource() == null ? "" : policy.policySource().name()
        );
        preferenceBuilder.policyId(policy.policyId() == null ? "" : policy.policyId().toString());
        preferenceBuilder.modelVersionId(
            policy.modelVersionId() == null ? "" : policy.modelVersionId().toString()
        );
        preferenceBuilder.resolvedDeviceType(blankToEmpty(deviceType));
        preferenceBuilder.canaryPercent(policy.canaryPercent());
        boolean canaryIncluded =
            policy.mode() != MlDeploymentMode.CANARY ||
            mlRuntimeDecisionService.canaryIncluded(
                accountId,
                deviceId,
                policy.modelVersionId(),
                policy.canaryPercent()
            );
        preferenceBuilder.canaryIncluded(canaryIncluded);
        String compatibilityFallbackReason = "";
        if (accountId != null && policy.modelVersionId() != null) {
            MlModelVersionDto model = mlModelRegistryService.getModel(
                accountId,
                policy.modelVersionId()
            );
            Map<String, Object> modelMetrics =
                model.evaluationMetricsJson() == null || model.evaluationMetricsJson().isBlank()
                    ? Map.of()
                    : predictionTraceService.readJson(model.evaluationMetricsJson());
            preferenceBuilder.requiredFeatureSchemaHash(
                model.featureSchemaHash() == null ? "" : model.featureSchemaHash()
            );
            preferenceBuilder.requiredFeatureContractVersion(
                string(modelMetrics.get("requiredFeatureContractVersion")).isBlank()
                    ? FeatureVectorContract.CONTRACT_VERSION
                    : string(modelMetrics.get("requiredFeatureContractVersion"))
            );
            preferenceBuilder.requiredFeatureContractMetadataHash(
                string(modelMetrics.get("requiredFeatureContractMetadataHash"))
            );
            preferenceBuilder.requiredFeatureSetVersion(
                string(modelMetrics.get("featureSetVersion"))
            );
            preferenceBuilder.requiredFeatureGeneratorVersion(
                string(modelMetrics.get("featureGeneratorVersion"))
            );
            preferenceBuilder.requiredFeatureTemplateId(
                string(modelMetrics.get("featureTemplateId"))
            );
            preferenceBuilder.requiredFeatureTemplateVersion(
                string(modelMetrics.get("featureTemplateVersion"))
            );
            preferenceBuilder.requiredHorizonDays(
                optionalInteger(modelMetrics.get("featureSnapshotHorizonDays"))
            );
            preferenceBuilder.requiredFeatureConfigHash(
                string(modelMetrics.get("requiredFeatureConfigHash"))
            );
            preferenceBuilder.modelFamily(model.family() == null ? "" : model.family().name());
            preferenceBuilder.modelVersion(Integer.toString(model.version()));
            preferenceBuilder.modelStatus(model.status() == null ? "" : model.status().name());
            preferenceBuilder.modelType(mlRuntimeDecisionService.runtimeModelType(model));
            preferenceBuilder.artifactUri(model.artifactUri() == null ? "" : model.artifactUri());
            preferenceBuilder.manifestUri(
                model.artifactManifestUri() == null ? "" : model.artifactManifestUri()
            );
            preferenceBuilder.artifactHash(
                model.artifactHash() == null ? "" : model.artifactHash()
            );
            preferenceBuilder.artifactSizeBytes(
                model.artifactSizeBytes() == null ? 0L : model.artifactSizeBytes()
            );
            compatibilityFallbackReason = mlRuntimeDecisionService.compatibilityFallbackReason(
                model,
                deviceType,
                metric
            );
        }
        var modelPreference = preferenceBuilder.build();
        boolean maintenanceOverlap = maintenanceWindows
            .stream()
            .anyMatch(MaintenanceWindowReadModel::isExcludeFromMl);
        boolean disabled =
            !settings.mlEnabled() || settings.predictionMode() == PredictionMode.RULE_BASED_ONLY;
        boolean suppressMl =
            disabled ||
            (maintenanceOverlap &&
                settings.maintenanceBehavior() == MaintenanceMlBehavior.SUPPRESS_ML) ||
            !canaryIncluded ||
            !compatibilityFallbackReason.isBlank();
        List<MlExcludedInterval> excludedIntervals = maintenanceWindows
            .stream()
            .filter(MaintenanceWindowReadModel::isExcludeFromMl)
            .map(window ->
                new MlExcludedInterval(
                    window.getMaintenanceWindowId(),
                    window.getStartAt(),
                    window.getEndAt(),
                    "MAINTENANCE"
                )
            )
            .toList();
        MlInferenceRequest inferenceRequest = new MlInferenceRequest(
            deviceId,
            now,
            metrics,
            excludedIntervals,
            baselineContext,
            recentWindow,
            modelPreference
        );
        MlInferenceResponse response = suppressMl
            ? null
            : mlClient.requestPrediction(
                  deviceId,
                  now,
                  metrics,
                  excludedIntervals,
                  baselineContext,
                  recentWindow,
                  modelPreference
              );
        if (!suppressMl && policy.mode() == MlDeploymentMode.SHADOW && response != null) {
            computedPredictions.add(
                predictionTraceService.buildPredictionResult(
                    deviceId,
                    response,
                    now,
                    inputHash,
                    "SHADOW",
                    policy,
                    true,
                    "",
                    deviceType,
                    canaryIncluded
                )
            );
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
                    ? policy.fallbackReason() == null
                        ? "ML_UNAVAILABLE_OR_BASELINE_FALLBACK"
                        : policy.fallbackReason()
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
        String predictionStatus =
            response == null || response.predictionStatus() == null
                ? status
                : response.predictionStatus();
        String responseInputHash =
            response == null || response.inputHash() == null || response.inputHash().isBlank()
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
            canaryIncluded
        );
        result.setStatus(predictionStatus);
        Map<String, Object> payload = predictionTraceService.predictionPayload(
            response,
            policy,
            predictionStatus,
            fallbackReason,
            false,
            responseInputHash,
            deviceType,
            canaryIncluded
        );
        payload.put("availabilityStatus", status);
        payload.put("generatedFeatureContract", inferenceRequest.featureContract());
        payload.put("featureGenerationStatus", inferenceRequest.featureGenerationStatus());
        payload.put("featureGenerationErrors", inferenceRequest.featureGenerationErrors());
        payload.put(
            "requiredFeatureSchemaHash",
            string(modelPreference.requiredFeatureSchemaHash())
        );
        payload.put(
            "requiredFeatureContractMetadataHash",
            string(modelPreference.requiredFeatureContractMetadataHash())
        );
        payload.put(
            "generatedFeatureContractMetadataHash",
            string(inferenceRequest.featureContract().get("contractMetadataHash"))
        );
        payload.put(
            "confidence",
            response == null
                ? 0
                : maintenanceOverlap &&
                    settings.maintenanceBehavior() == MaintenanceMlBehavior.REDUCE_CONFIDENCE
                  ? response.confidence() * settings.confidenceWarningThreshold()
                  : response.confidence()
        );
        payload.put("reason", disabled ? "disabled" : suppressMl ? "maintenance" : "");
        payload.put("status", status);
        result.setPredictionJson(predictionTraceService.writeJson(payload));
        computedPredictions.add(result);
        return new PreparedPrediction(result, List.copyOf(computedPredictions));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private Integer optionalInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return null;
        return Integer.valueOf(value.toString());
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
