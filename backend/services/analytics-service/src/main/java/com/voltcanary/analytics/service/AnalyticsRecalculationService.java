package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.anomaly.AnomalyDetector;
import com.voltcanary.analytics.anomaly.UserRuleAnomalyDetector;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.baseline.BaselineCalculator;
import com.voltcanary.analytics.baseline.BaselineStats;
import com.voltcanary.analytics.config.AnalyticsMetricProperties;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.dto.AnomalyResponse;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import com.voltcanary.analytics.dto.BaselineResponse;
import com.voltcanary.analytics.dto.FeatureSetResponse;
import com.voltcanary.analytics.dto.PredictionResponse;
import com.voltcanary.analytics.dto.RecommendationResponse;
import com.voltcanary.analytics.dto.RiskResponse;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.FeatureSet;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlExcludedInterval;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.producer.AnalyticsOutboxService;
import com.voltcanary.analytics.domain.Recommendation;
import com.voltcanary.analytics.recommendation.RecommendationGenerator;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.DeviceBaselineRepository;
import com.voltcanary.analytics.repository.FeatureSetRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.repository.RiskScoreRepository;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.risk.RiskScoreCalculator;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRecalculationService.class);
    private static final String BASELINE_ALGORITHM_VERSION = "baseline-basic-v1";

    private final TelemetryClient telemetryClient;
    private final DeviceBaselineRepository baselineRepository;
    private final FeatureSetRepository featureSetRepository;
    private final AnomalyRepository anomalyRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final RecommendationRepository recommendationRepository;
    private final PredictionResultRepository predictionResultRepository;
    private final ObjectMapper objectMapper;
    private final AnalyticsOutboxService outboxService;
    private final MlClient mlClient;
    private final AnalyticsMetricProperties metricProperties;
    private final BaselineCalculator baselineCalculator;
    private final AnomalyDetector anomalyDetector;
    private final UserRuleAnomalyDetector userRuleAnomalyDetector;
    private final AlertRuleClient alertRuleClient;
    private final RiskScoreCalculator riskScoreCalculator;
    private final RecommendationGenerator recommendationGenerator;
    private final MeterRegistry meterRegistry;
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;
    private final MlPolicyService mlPolicyService;
    private final MlModelRegistryService mlModelRegistryService;
    private final DeviceMetadataClient deviceMetadataClient;
    private final AnalyticsPredictionTraceService predictionTraceService;
    private final AnalyticsMlRuntimeDecisionService mlRuntimeDecisionService;
    private final AnalyticsBaselineFeatureService baselineFeatureService;
    private final AnalyticsMaintenanceTelemetryFilterService maintenanceTelemetryFilterService;
    private final AnalyticsMlRecommendationService mlRecommendationService;

    public AnalyticsRecalculationService(
            TelemetryClient telemetryClient,
            DeviceBaselineRepository baselineRepository,
            FeatureSetRepository featureSetRepository,
            AnomalyRepository anomalyRepository,
            RiskScoreRepository riskScoreRepository,
            RecommendationRepository recommendationRepository,
            PredictionResultRepository predictionResultRepository,
            ObjectMapper objectMapper,
            AnalyticsOutboxService outboxService,
            MlClient mlClient,
            AnalyticsMetricProperties metricProperties,
            BaselineCalculator baselineCalculator,
            AnomalyDetector anomalyDetector,
            UserRuleAnomalyDetector userRuleAnomalyDetector,
            AlertRuleClient alertRuleClient,
            RiskScoreCalculator riskScoreCalculator,
            RecommendationGenerator recommendationGenerator,
            MeterRegistry meterRegistry,
            MaintenanceWindowProjectionService maintenanceWindowProjectionService,
            MlPolicyService mlPolicyService,
            MlModelRegistryService mlModelRegistryService,
            DeviceMetadataClient deviceMetadataClient,
            AnalyticsPredictionTraceService predictionTraceService,
            AnalyticsMlRuntimeDecisionService mlRuntimeDecisionService,
            AnalyticsBaselineFeatureService baselineFeatureService,
            AnalyticsMaintenanceTelemetryFilterService maintenanceTelemetryFilterService,
            AnalyticsMlRecommendationService mlRecommendationService) {
        this.telemetryClient = telemetryClient;
        this.baselineRepository = baselineRepository;
        this.featureSetRepository = featureSetRepository;
        this.anomalyRepository = anomalyRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.recommendationRepository = recommendationRepository;
        this.predictionResultRepository = predictionResultRepository;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
        this.mlClient = mlClient;
        this.metricProperties = metricProperties;
        this.baselineCalculator = baselineCalculator;
        this.anomalyDetector = anomalyDetector;
        this.userRuleAnomalyDetector = userRuleAnomalyDetector;
        this.alertRuleClient = alertRuleClient;
        this.riskScoreCalculator = riskScoreCalculator;
        this.recommendationGenerator = recommendationGenerator;
        this.meterRegistry = meterRegistry;
        this.maintenanceWindowProjectionService = maintenanceWindowProjectionService;
        this.mlPolicyService = mlPolicyService;
        this.mlModelRegistryService = mlModelRegistryService;
        this.deviceMetadataClient = deviceMetadataClient;
        this.predictionTraceService = predictionTraceService;
        this.mlRuntimeDecisionService = mlRuntimeDecisionService;
        this.baselineFeatureService = baselineFeatureService;
        this.maintenanceTelemetryFilterService = maintenanceTelemetryFilterService;
        this.mlRecommendationService = mlRecommendationService;
    }

    @Transactional
    public RecalculationResult recalculate(UUID deviceId) {
        return recalculate(deviceId, Instant.now());
    }

    @Transactional
    public RecalculationResult recalculate(UUID deviceId, Instant windowEnd) {
        Timer.Sample timer = Timer.start(meterRegistry);
        Instant now = Instant.now();
        Instant windowStart = windowEnd.minus(metricProperties.getDefaultWindow());
        String period = metricProperties.getPeriodLabel();
        List<String> metrics = metricProperties.getDefaultMetrics();
        List<MaintenanceWindowReadModel> maintenanceWindows =
                maintenanceWindowProjectionService.activeOverlapping(deviceId, windowStart, windowEnd);
        DeviceMetadataClient.DeviceMetadata deviceMetadata = deviceMetadataClient.metadata(deviceId);
        String deviceType = deviceMetadata == null ? "" : blankToEmpty(deviceMetadata.type());
        com.voltcanary.analytics.dto.MlSettingsResponse mlSettings = mlPolicyService.effectiveForAnalytics(
                deviceId,
                maintenanceWindows.stream()
                        .map(MaintenanceWindowReadModel::getAccountId)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(deviceMetadata == null ? null : deviceMetadata.accountId()));
        UUID accountId = mlSettings.accountId() == null && deviceMetadata != null
                ? deviceMetadata.accountId()
                : mlSettings.accountId();
        MlResolvedDeploymentPolicyResponse policy = accountId == null
                ? new MlResolvedDeploymentPolicyResponse(null, null, null, MlDeploymentMode.DISABLED, true, "ACCOUNT_UNKNOWN")
                : mlModelRegistryService.resolvePolicy(accountId, deviceId, deviceType);
        List<MaintenanceWindowReadModel> analyticsExclusions = maintenanceWindows.stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromAnalytics)
                .toList();
        List<TelemetryChartResponse> charts = new ArrayList<>();
        List<BaselineStats> baselines = new ArrayList<>();
        Map<String, TelemetryChartResponse> chartByMetric = new HashMap<>();
        Map<String, BaselineStats> baselineByMetric = new HashMap<>();
        List<Double> allValues = new ArrayList<>();
        DeviceBaseline savedBaseline = null;
        int excludedSamples = 0;

        for (String metric : metrics) {
            TelemetryChartResponse rawChart = telemetryClient.readings(deviceId, metric, windowStart, windowEnd);
            TelemetryChartResponse chart = filterChart(rawChart, analyticsExclusions);
            excludedSamples += excludedSampleCount(rawChart, chart);
            List<Double> values = chart == null ? List.of() : chart.points().stream()
                    .map(TelemetryChartResponse.Point::value)
                    .sorted()
                    .toList();
            BaselineStats baseline = baselineCalculator.calculate(values);
            charts.add(chart);
            baselines.add(baseline);
            chartByMetric.put(metric, chart);
            baselineByMetric.put(metric, baseline);
            allValues.addAll(values);
        }

        if (allValues.isEmpty() && excludedSamples > 0) {
            meterRegistry.counter("analytics.maintenance.jobs_skipped", "reason", "full_coverage").increment();
            timer.stop(meterRegistry.timer("analytics.recalculation.duration", "status", "skipped"));
            String inputHash = inputHash(deviceId, windowStart, windowEnd, charts, maintenanceWindows);
            return RecalculationResult.skipped(windowStart, windowEnd, inputHash, "MAINTENANCE_FULL_COVERAGE");
        }

        for (int index = 0; index < metrics.size(); index++) {
            DeviceBaseline baselineEntity = saveBaseline(deviceId, metrics.get(index), period, baselines.get(index), now);
            saveFeatureSet(
                    deviceId,
                    metrics.get(index),
                    period,
                    windowStart,
                    windowEnd,
                    baselines.get(index),
                    charts.get(index),
                    excludedSamples,
                    maintenanceWindows);
            if (savedBaseline == null) {
                savedBaseline = baselineEntity;
            }
        }

        BaselineStats primaryBaseline = baselines.isEmpty() ? baselineCalculator.calculate(List.of()) : baselines.get(0);
        TelemetryChartResponse primaryChart = charts.isEmpty() ? null : charts.get(0);
        String inputHash = inputHash(deviceId, windowStart, windowEnd, charts, maintenanceWindows);
        double previousRiskScore = riskScoreRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
                .map(RiskScore::getScore)
                .orElse(0.0);

        anomalyRepository.supersedeActiveByDeviceId(deviceId, now);
        recommendationRepository.supersedeActiveByDeviceId(deviceId, now);

        List<Anomaly> detected = new ArrayList<>();
        for (int index = 0; index < charts.size(); index++) {
            detected.addAll(anomalyDetector.detect(
                    deviceId,
                    charts.get(index),
                    baselines.get(index),
                    windowStart,
                    windowEnd,
                    inputHash,
                    now));
        }
        for (AlertRuleSnapshot rule : alertRuleClient.effectiveRules(deviceId, inputHash)) {
            if (rule.metric() == null || rule.metric().isBlank()) {
                continue;
            }
            TelemetryChartResponse chart = chartByMetric.computeIfAbsent(rule.metric(), metric -> filterChart(
                    telemetryClient.readings(deviceId, metric, windowStart, windowEnd),
                    analyticsExclusions));
            BaselineStats baseline = baselineByMetric.computeIfAbsent(rule.metric(), metric -> {
                List<Double> values = chart == null || chart.points() == null ? List.of() : chart.points().stream()
                        .map(TelemetryChartResponse.Point::value)
                        .sorted()
                        .toList();
                return baselineCalculator.calculate(values);
            });
            detected.addAll(userRuleAnomalyDetector.detect(
                    deviceId,
                    rule,
                    chart,
                    baseline,
                    windowStart,
                    windowEnd,
                    inputHash,
                    now));
        }
        List<Anomaly> anomalies = anomalyRepository.saveAll(detected);
        RiskScore risk = riskScoreRepository.save(riskScoreCalculator.calculate(
                deviceId,
                anomalies,
                allValues.size(),
                windowStart,
                windowEnd,
                inputHash,
                now));
        PredictionResult prediction = predictionResultRepository.save(
                prediction(
                        deviceId,
                        primaryChart,
                        primaryBaseline,
                        risk,
                        now,
                        maintenanceWindows,
                        windowStart,
                        windowEnd,
                        inputHash,
                        accountId,
                        deviceType,
                        mlSettings,
                        policy));
        List<Recommendation> generatedRecommendations = new ArrayList<>(
                recommendationGenerator.generate(deviceId, anomalies, now));
        generatedRecommendations.addAll(mlRecommendations(deviceId, prediction, now));
        List<Recommendation> recommendations = recommendationRepository.saveAll(generatedRecommendations);
        anomalies.forEach(outboxService::enqueueAnomaly);
        outboxService.enqueueRiskScore(risk, previousRiskScore);

        anomalies.forEach(anomaly -> meterRegistry.counter(
                "analytics.anomalies.detected",
                "type",
                anomaly.getType() == null ? "unknown" : anomaly.getType(),
                "severity",
                anomaly.getSeverity() == null ? "unknown" : anomaly.getSeverity()).increment());
        timer.stop(meterRegistry.timer("analytics.recalculation.duration", "status", "success"));
        log.info(
                "analyticsRecalculationCompleted deviceId={} windowStart={} windowEnd={} inputHash={} metrics={} anomalies={} riskLevel={} riskScore={}",
                deviceId,
                windowStart,
                windowEnd,
                inputHash,
                metrics.size(),
                anomalies.size(),
                risk.getLevel(),
                risk.getScore());
        return new RecalculationResult(
                savedBaseline,
                anomalies,
                risk,
                recommendations,
                prediction,
                windowStart,
                windowEnd,
                inputHash,
                false,
                null);
    }

    @Transactional(readOnly = true)
    public Optional<BaselineResponse> baseline(UUID deviceId) {
        return baselineRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
                .map(this::toBaselineResponse);
    }

    @Transactional(readOnly = true)
    public List<AnomalyResponse> anomalies(UUID deviceId) {
        return anomalyRepository.findTop100ByDeviceIdAndStatusOrderByDetectedAtDesc(deviceId, "ACTIVE").stream()
                .map(this::toAnomalyResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeatureSetResponse> features(UUID deviceId) {
        return featureSetRepository.findTop100ByDeviceIdOrderByWindowEndDesc(deviceId).stream()
                .map(this::toFeatureSetResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<RiskResponse> risk(UUID deviceId) {
        return riskScoreRepository.findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
                .map(score -> toRiskResponse(score, anomalies(deviceId)));
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> recommendations(UUID deviceId) {
        return recommendationRepository.findTop100ByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "ACTIVE").stream()
                .map(this::toRecommendationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PredictionResponse> latestPrediction(UUID deviceId) {
        return predictionResultRepository.findFirstByDeviceIdAndShadowPredictionFalseOrderByCreatedAtDesc(deviceId)
                .map(this::toPredictionResponse);
    }

    private DeviceBaseline saveBaseline(UUID deviceId, String metric, String period, BaselineStats baseline, Instant now) {
        return baselineFeatureService.saveBaseline(deviceId, metric, period, baseline, now);
    }

    private FeatureSet saveFeatureSet(
            UUID deviceId,
            String metric,
            String period,
            Instant windowStart,
            Instant windowEnd,
            BaselineStats baseline,
            TelemetryChartResponse chart,
            int excludedSamples,
            List<MaintenanceWindowReadModel> maintenanceWindows) {
        return baselineFeatureService.saveFeatureSet(
                deviceId,
                metric,
                period,
                windowStart,
                windowEnd,
                baseline,
                chart,
                excludedSamples,
                maintenanceWindows);
    }

    private PredictionResult prediction(
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
            com.voltcanary.analytics.dto.MlSettingsResponse settings,
            MlResolvedDeploymentPolicyResponse policy) {
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
        metrics.put("latestValue", latestValue(chart));
        metrics.put("deviceType", blankToEmpty(deviceType));
        if (!metric.isBlank()) {
            metrics.put(metric, latestValue(chart));
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
        boolean allowBaseline = settings.predictionMode() == com.voltcanary.analytics.domain.PredictionMode.MODEL_OR_BASELINE_HEURISTIC
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
                || canaryIncluded(accountId, deviceId, policy.modelVersionId(), policy.canaryPercent());
        modelPreference.put("canaryIncluded", canaryIncluded);
        String compatibilityFallbackReason = "";
        if (accountId != null && policy.modelVersionId() != null) {
            MlModelVersionDto model = mlModelRegistryService.getModel(accountId, policy.modelVersionId());
            Map<String, Object> modelMetrics = model.evaluationMetricsJson() == null || model.evaluationMetricsJson().isBlank()
                    ? Map.of()
                    : readJson(model.evaluationMetricsJson());
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
            modelPreference.put("modelType", runtimeModelType(model));
            modelPreference.put("artifactUri", model.artifactUri() == null ? "" : model.artifactUri());
            modelPreference.put("manifestUri", model.artifactManifestUri() == null ? "" : model.artifactManifestUri());
            modelPreference.put("artifactHash", model.artifactHash() == null ? "" : model.artifactHash());
            modelPreference.put("artifactSizeBytes", model.artifactSizeBytes() == null ? 0L : model.artifactSizeBytes());
            compatibilityFallbackReason = compatibilityFallbackReason(model, deviceType, metric);
        }
        boolean maintenanceOverlap = maintenanceWindows.stream().anyMatch(MaintenanceWindowReadModel::isExcludeFromMl);
        boolean disabled = !settings.mlEnabled() || settings.predictionMode() == com.voltcanary.analytics.domain.PredictionMode.RULE_BASED_ONLY;
        boolean suppressMl = disabled || (maintenanceOverlap
                && settings.maintenanceBehavior() == com.voltcanary.analytics.domain.MaintenanceMlBehavior.SUPPRESS_ML)
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
        com.voltcanary.analytics.ml.MlInferenceRequest inferenceRequest = new com.voltcanary.analytics.ml.MlInferenceRequest(
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
            predictionResultRepository.save(buildPredictionResult(
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
        PredictionResult result = buildPredictionResult(
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
        Map<String, Object> payload = predictionPayload(
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
                : (maintenanceOverlap && settings.maintenanceBehavior() == com.voltcanary.analytics.domain.MaintenanceMlBehavior.REDUCE_CONFIDENCE
                        ? response.confidence() * settings.confidenceWarningThreshold()
                        : response.confidence()));
        payload.put("reason", disabled ? "disabled" : suppressMl ? "maintenance" : "");
        payload.put("status", status);
        result.setPredictionJson(writeJson(payload));
        return result;
    }

    private PredictionResult buildPredictionResult(
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
        return predictionTraceService.buildPredictionResult(
                deviceId,
                response,
                now,
                inputHash,
                predictionStatus,
                policy,
                shadowPrediction,
                fallbackReason,
                deviceType,
                canaryIncluded);
    }

    private Map<String, Object> predictionPayload(
            MlInferenceResponse response,
            MlResolvedDeploymentPolicyResponse policy,
            String predictionStatus,
            String fallbackReason,
            boolean shadowPrediction,
            String inputHash,
            String deviceType,
            boolean canaryIncluded) {
        return predictionTraceService.predictionPayload(
                response,
                policy,
                predictionStatus,
                fallbackReason,
                shadowPrediction,
                inputHash,
                deviceType,
                canaryIncluded);
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
                baseline.getSampleCount() == null ? (int) number(values.get("sampleCount")) : baseline.getSampleCount(),
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

    private FeatureSetResponse toFeatureSetResponse(FeatureSet featureSet) {
        return new FeatureSetResponse(
                featureSet.getId(),
                featureSet.getDeviceId(),
                featureSet.getWindowStart(),
                featureSet.getWindowEnd(),
                readJson(featureSet.getFeaturesJson()));
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
        Object signals = values.get("signals");
        Object riskDrivers = values.get("riskDrivers");
        Object recommendedActions = values.get("recommendedActions");
        Object trainingDataWindow = values.get("trainingDataWindow");
        return new PredictionResponse(
                prediction.getDeviceId(),
                prediction.getModelId() == null ? string(values.get("responseModelVersionId")) : prediction.getModelId().toString(),
                string(values.get("modelFamily")),
                prediction.getModelName() == null ? string(values.get("modelName")) : prediction.getModelName(),
                prediction.getModelVersion() == null ? string(values.get("modelVersion")) : prediction.getModelVersion(),
                string(values.get("modelType")),
                string(values.get("modelStatus")),
                prediction.getStatus() == null ? string(values.get("predictionStatus")) : prediction.getStatus(),
                prediction.getPolicySource() == null ? string(values.get("policySource")) : prediction.getPolicySource(),
                string(values.get("deploymentMode")),
                string(values.get("resolvedDeviceType")),
                nullableInteger(values.get("canaryPercent")),
                nullableBoolean(values.get("canaryIncluded")),
                string(values.get("predictionPath")),
                prediction.getFallbackReason() == null ? string(values.get("fallbackReason")) : prediction.getFallbackReason(),
                prediction.isShadowPrediction(),
                prediction.getAnomalyScore() == null ? number(values.get("anomalyScore")) : prediction.getAnomalyScore(),
                prediction.getDegradationScore() == null ? number(values.get("degradationScore")) : prediction.getDegradationScore(),
                nullableNumber(values.get("failureRiskScore")),
                nullableInteger(values.get("failureHorizonDays")),
                nullableString(values.get("failureRiskBand")),
                prediction.getConfidence() == null ? number(values.get("confidence")) : prediction.getConfidence(),
                prediction.getInputHash() == null ? string(values.get("inputHash")) : prediction.getInputHash(),
                maps(riskDrivers),
                nullableNumber(values.get("labelCoverage")),
                trainingDataWindow instanceof Map<?, ?> window
                        ? (Map<String, Object>) window
                        : Map.of(),
                signals instanceof List<?> signalList
                        ? signalList.stream()
                                .filter(Map.class::isInstance)
                                .map(item -> (Map<String, Object>) item)
                                .toList()
                        : List.of(),
                explanations instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(),
                recommendedActions instanceof List<?> actionList
                        ? actionList.stream().map(Object::toString).toList()
                        : List.of(),
                prediction.getCreatedAt());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list
                ? list.stream()
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

    private List<Recommendation> mlRecommendations(UUID deviceId, PredictionResult prediction, Instant now) {
        return mlRecommendationService.mlRecommendations(deviceId, prediction, now);
    }

    private Recommendation mlRecommendation(
            UUID deviceId,
            String action,
            List<String> signalDescriptions,
            String riskLevel,
            Instant now) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setDeviceId(deviceId);
        recommendation.setTitle("Review ML prediction signal");
        recommendation.setDescription(signalDescriptions.isEmpty()
                ? action
                : action + " Signals: " + String.join("; ", signalDescriptions));
        recommendation.setPriority("CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel) ? "HIGH" : "MEDIUM");
        recommendation.setStatus("ACTIVE");
        recommendation.setCreatedAt(now);
        return recommendation;
    }

    private String writeJson(Map<String, Object> values) {
        return predictionTraceService.writeJson(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        return predictionTraceService.readJson(json);
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean canaryIncluded(UUID accountId, UUID deviceId, UUID modelVersionId, Integer canaryPercent) {
        return mlRuntimeDecisionService.canaryIncluded(accountId, deviceId, modelVersionId, canaryPercent);
    }

    private int hashBucket(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24)
                    | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String compatibilityFallbackReason(MlModelVersionDto model, String deviceType, String metric) {
        return mlRuntimeDecisionService.compatibilityFallbackReason(model, deviceType, metric);
    }

    private String runtimeModelType(MlModelVersionDto model) {
        return mlRuntimeDecisionService.runtimeModelType(model);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String json) {
        return mlRuntimeDecisionService.stringList(json);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double latestValue(TelemetryChartResponse chart) {
        return baselineFeatureService.latestValue(chart);
    }

    private String inputHash(
            UUID deviceId,
            Instant windowStart,
            Instant windowEnd,
            List<TelemetryChartResponse> charts,
            List<MaintenanceWindowReadModel> maintenanceWindows) {
        return predictionTraceService.inputHash(deviceId, windowStart, windowEnd, charts, maintenanceWindows);
    }

    private TelemetryChartResponse filterChart(
            TelemetryChartResponse chart,
            List<MaintenanceWindowReadModel> analyticsExclusions) {
        return maintenanceTelemetryFilterService.filterChart(chart, analyticsExclusions);
    }

    private int excludedSampleCount(TelemetryChartResponse rawChart, TelemetryChartResponse filteredChart) {
        return maintenanceTelemetryFilterService.excludedSampleCount(rawChart, filteredChart);
    }

    private boolean isInsideAny(Instant timestamp, List<MaintenanceWindowReadModel> windows) {
        return windows.stream().anyMatch(window ->
                !timestamp.isBefore(window.getStartAt()) && timestamp.isBefore(window.getEndAt()));
    }

    public record RecalculationResult(
            DeviceBaseline baseline,
            List<Anomaly> anomalies,
            RiskScore riskScore,
            List<Recommendation> recommendations,
            PredictionResult prediction,
            Instant windowStart,
            Instant windowEnd,
            String inputHash,
            boolean skipped,
            String skipReason) {

        static RecalculationResult skipped(Instant windowStart, Instant windowEnd, String inputHash, String skipReason) {
            return new RecalculationResult(
                    null,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    windowStart,
                    windowEnd,
                    inputHash,
                    true,
                    skipReason);
        }
    }

}
