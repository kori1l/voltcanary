package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.ml.MlTrainingMetricRow;
import com.voltcanary.analytics.ml.MlTrainingRequest;
import com.voltcanary.analytics.ml.MlTrainingResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ml.training.worker-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MlTrainingWorker {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(15);

    private final MlTrainingRunRepository trainingRunRepository;
    private final MlTrainingLeases leases;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureRowRepository featureRowRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlRegistryFence registryFence;
    private final MlClient mlClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    @Scheduled(fixedDelayString = "${app.ml.training.worker-delay:5000}")
    public void scheduledExecuteNextQueuedRun() {
        executeNextQueuedRun();
    }

    public Optional<MlTrainingRun> executeNextQueuedRun() {
        recoverStaleRuns();
        return leases.claim(workerId + ":" + UUID.randomUUID(), LEASE_DURATION).map(this::execute);
    }

    MlTrainingRun execute(MlTrainingRun run) {
        UUID runId = run.getId();
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant started = run.getStartedAt() == null ? Instant.now() : run.getStartedAt();
        log.info("mlTrainingStarted runId={} accountId={} datasetVersionId={} featureSnapshotId={} modelFamily={} algorithm={}",
                run.getId(), run.getAccountId(), run.getDatasetVersionId(), run.getFeatureSnapshotId(), run.getModelFamily(), run.getAlgorithm());

        try {
            assertNotCanceled(run);
            MlDatasetVersion datasetVersion = datasetVersionRepository.findById(run.getDatasetVersionId())
                    .orElseThrow(() -> new IllegalStateException("Dataset version is missing"));
            if (datasetVersion.getStatus() != MlDatasetStatus.READY) {
                throw new IllegalStateException("Dataset version is not READY");
            }
            heartbeat(run);
            assertNotCanceled(run);
            MlFeatureSnapshot featureSnapshot = featureSnapshotRepository.findById(run.getFeatureSnapshotId())
                    .orElseThrow(() -> new IllegalStateException("Feature snapshot is missing"));
            if (featureSnapshot.getRowCount() <= 0) {
                throw new IllegalStateException("Feature snapshot has no rows");
            }
            List<MlFeatureRow> featureRows = featureRowRepository.findByFeatureSnapshotIdOrderByPredictionAtAsc(featureSnapshot.getId());
            List<MlTrainingMetricRow> trainingRows = trainingRows(featureRows);
            if (trainingRows.isEmpty()) {
                throw new IllegalStateException("Feature snapshot has no numeric feature rows");
            }
            heartbeat(run);
            assertNotCanceled(run);

            MlTrainingResponse trainingResponse = mlClient.requestTraining(new MlTrainingRequest(
                    run.getId(),
                    idempotencyKey(run),
                    "model-" + run.getId(),
                    datasetVersion.getId(),
                    run.getAccountId(),
                    datasetVersion.getSourceType().name(),
                    run.getModelFamily().name(),
                    run.getAlgorithm(),
                    integerOption(run.getHyperparametersJson(), "failureHorizonDays"),
                    integerOption(run.getHyperparametersJson(), "minimumPositiveLabels"),
                    doubleOption(run.getHyperparametersJson(), "minimumLabelCoverage"),
                    mapOption(run.getHyperparametersJson(), "approvalGateProfile"),
                    trainingRows));
            if (trainingResponse == null || !"SUCCEEDED".equals(trainingResponse.status())) {
                throw new IllegalStateException("ML-service training did not produce a succeeded model artifact");
            }
            heartbeat(run);
            assertNotCanceled(run);
            UUID modelVersionId = UUID.fromString(trainingResponse.modelVersionId());
            Map<String, Object> smokeMetrics = smokeMetrics(featureRows);
            MlInferenceResponse smokeResponse = mlClient.requestPrediction(
                    UUID.fromString(trainingRows.get(0).deviceId()),
                    Instant.parse(trainingRows.get(0).timestamp()),
                    smokeMetrics,
                    List.of(),
                    null,
                    Map.of("sampleCount", trainingRows.size()),
                    Map.of(
                            "modelVersionId", modelVersionId.toString(),
                            "artifactUri", trainingResponse.artifactUri() == null ? "" : trainingResponse.artifactUri(),
                            "manifestUri", trainingResponse.manifestUri() == null ? "" : trainingResponse.manifestUri(),
                            "artifactHash", trainingResponse.artifactHash() == null ? "" : trainingResponse.artifactHash(),
                            "artifactSizeBytes", trainingResponse.artifactSizeBytes() == null ? 0L : trainingResponse.artifactSizeBytes(),
                            "modelFamily", run.getModelFamily().name(),
                            "modelVersion", trainingResponse.modelVersion() == null ? "" : trainingResponse.modelVersion(),
                            "modelType", modelType(run),
                            "modelStatus", "READY",
                            "allowBaselineHeuristic", false));
            if (smokeResponse == null
                    || !"TRAINED_MODEL".equals(smokeResponse.predictionStatus())
                    || !modelVersionId.toString().equals(smokeResponse.modelVersionId())) {
                throw new IllegalStateException("ML-service model artifact failed load-test inference");
            }
            heartbeat(run);
            assertNotCanceled(run);

            Map<String, Object> metrics = new LinkedHashMap<>(trainingResponse.metrics() == null ? Map.of() : trainingResponse.metrics());
            metrics.put("modelFamily", run.getModelFamily().name());
            metrics.put("algorithm", run.getAlgorithm());
            metrics.put("featureSnapshotRows", featureSnapshot.getRowCount());
            metrics.put("featureSetVersion", featureSnapshot.getFeatureSetVersion());
            metrics.put("featureGeneratorVersion", featureSnapshot.getCodeConfigVersion());
            metrics.put("featureTemplateId", featureSnapshot.getFeatureTemplateId());
            metrics.put("featureTemplateVersion", featureSnapshot.getFeatureTemplateVersion());
            metrics.put("featureSnapshotHorizonDays", featureSnapshot.getHorizonDays());
            metrics.put("requiredFeatureContractVersion", FeatureVectorContract.CONTRACT_VERSION);
            metrics.put("requiredFeatureContractMetadataHash", featureContractMetadataHash(featureSnapshot));
            metrics.put("requiredFeatureSchemaHash", featureSnapshot.getFeatureSchemaHash());
            metrics.put("requiredFeatureConfigHash", featureSnapshot.getFeatureConfigHash());
            metrics.put("trainingFeatureValues", trainingRows.size());
            metrics.put("rejectedFeatureRows", featureSnapshot.getRejectedRowCount());
            metrics.put("loadTestPredictionStatus", smokeResponse.predictionStatus());
            String metricsJson = writeJson(metrics);

            run.setMetricsJson(metricsJson);
            run.setArtifactUri(trainingResponse.artifactUri());
            run.setLogsSummary("Training delegated to ML-service and load-test inference passed.");
            run.setStatus(MlTrainingRunStatus.SUCCEEDED);
            run.setCompletedAt(Instant.now());
            run = leases.finish(run, () -> registerCandidateModel(runId, datasetVersion, featureSnapshot, metricsJson, trainingResponse, modelVersionId));
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "succeeded", "algorithm", run.getAlgorithm()));
            log.info("mlTrainingSucceeded runId={} accountId={} durationMs={} artifactUri={} trainingRows={}",
                    run.getId(), run.getAccountId(), Duration.between(started, run.getCompletedAt()).toMillis(), trainingResponse.artifactUri(), trainingRows.size());
            return run;
        } catch (TrainingCanceledException exception) {
            run.setStatus(MlTrainingRunStatus.CANCELED);
            run.setFailureReason(null);
            run.setLogsSummary("Training canceled before candidate model registration.");
            run.setCompletedAt(Instant.now());
            try { run = leases.finish(run, () -> { }); }
            catch (MlTrainingLeases.Lost lost) { return trainingRunRepository.findById(run.getId()).orElse(run); }
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "canceled", "algorithm", run.getAlgorithm()));
            log.info("mlTrainingCanceled runId={} accountId={} durationMs={}",
                    run.getId(), run.getAccountId(), Duration.between(started, run.getCompletedAt()).toMillis());
            return run;
        } catch (MlTrainingLeases.Lost lost) {
            return trainingRunRepository.findById(run.getId()).orElse(run);
        } catch (Exception exception) {
            run.setStatus(MlTrainingRunStatus.FAILED);
            run.setFailureReason(publicReason(exception));
            run.setLogsSummary("Training failed before model artifact registration.");
            run.setCompletedAt(Instant.now());
            try { run = leases.finish(run, () -> { }); }
            catch (MlTrainingLeases.Lost lost) { return trainingRunRepository.findById(run.getId()).orElse(run); }
            meterRegistry.counter("analytics.ml.training.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "failed", "algorithm", run.getAlgorithm()));
            log.warn("mlTrainingFailed runId={} accountId={} durationMs={} reason={}",
                    run.getId(), run.getAccountId(), Duration.between(started, run.getCompletedAt()).toMillis(), run.getFailureReason());
            return run;
        }
    }

    void recoverStaleRuns() {
        int recovered = leases.recover();
        if (recovered > 0) meterRegistry.counter("analytics.ml.training.stale.recovered").increment(recovered);
    }

    private void heartbeat(MlTrainingRun run) {
        leases.heartbeat(run, LEASE_DURATION);
    }

    private void assertNotCanceled(MlTrainingRun run) {
        MlTrainingRun latest = trainingRunRepository.findById(run.getId()).orElse(run);
        if (latest.isCancellationRequested() || latest.getStatus() == MlTrainingRunStatus.CANCELED) {
            run.setCancellationRequested(true);
            throw new TrainingCanceledException();
        }
    }

    private void registerCandidateModel(
            UUID runId,
            MlDatasetVersion datasetVersion,
            MlFeatureSnapshot featureSnapshot,
            String metricsJson,
            MlTrainingResponse trainingResponse,
            UUID modelVersionId) {
        MlTrainingRun run = trainingRunRepository.findById(runId).orElseThrow();
        registryFence.account(run.getAccountId());
        MlModelVersion model = new MlModelVersion();
        model.setId(modelVersionId);
        model.setAccountId(run.getAccountId());
        model.setModelKey("account-" + run.getAccountId() + "-" + run.getModelFamily().name().toLowerCase());
        model.setVersion(modelVersionRepository.findByModelKeyOrderByVersionDesc(model.getModelKey()).stream()
                .mapToInt(MlModelVersion::getVersion)
                .max()
                .orElse(0) + 1);
        model.setFamily(run.getModelFamily());
        model.setAlgorithm(run.getAlgorithm());
        model.setStatus(MlModelStatus.CANDIDATE);
        model.setTrainingRunId(run.getId());
        model.setDatasetVersionId(datasetVersion.getId());
        model.setFeatureSnapshotId(featureSnapshot.getId());
        model.setFeatureSchemaHash(featureSnapshot.getFeatureSchemaHash());
        model.setArtifactUri(trainingResponse.artifactUri());
        model.setArtifactManifestUri(trainingResponse.manifestUri());
        model.setArtifactHash(trainingResponse.artifactHash());
        model.setArtifactSizeBytes(trainingResponse.artifactSizeBytes());
        model.setCompatibleDeviceTypesJson(writeJson(List.of()));
        model.setSupportedMetricsJson(writeJson(supportedMetrics(featureSnapshot.getId())));
        model.setEvaluationMetricsJson(metricsJson);
        model.setCreatedAt(Instant.now());
        modelVersionRepository.save(model);
    }

    private List<MlTrainingMetricRow> trainingRows(List<MlFeatureRow> featureRows) {
        return featureRows.stream()
                .flatMap(row -> numericFeatures(row).entrySet().stream()
                        .map(entry -> new MlTrainingMetricRow(
                                row.getPredictionAt() == null ? null : row.getPredictionAt().toString(),
                                row.getDeviceId() == null ? stableVirtualDeviceId(row) : row.getDeviceId().toString(),
                                entry.getKey(),
                                entry.getValue(),
                                null,
                                labelFor(row),
                                metadataFor(row))))
                .toList();
    }

    private Map<String, Object> metadataFor(MlFeatureRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("entityId", row.getEntityId());
        metadata.put("featureRowId", row.getId().toString());
        metadata.put("featureSnapshotId", row.getFeatureSnapshotId().toString());
        metadata.put("deviceType", row.getDeviceType() == null ? "" : row.getDeviceType());
        metadata.put("horizonDays", row.getHorizonDays());
        metadata.put("splitAssignment", row.getSplitAssignment());
        metadata.putAll(labelValues(row));
        return metadata;
    }

    private String labelFor(MlFeatureRow row) {
        Object label = labelValues(row).get("label");
        return label == null ? null : label.toString();
    }

    private Map<String, Double> numericFeatures(MlFeatureRow row) {
        try {
            Map<String, Object> values = objectMapper.readValue(row.getFeatureValuesJson(), new TypeReference<>() {});
            Map<String, Double> numeric = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (value instanceof Number number) {
                    numeric.put(key, number.doubleValue());
                }
            });
            return numeric;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse feature row values", exception);
        }
    }

    private Map<String, Object> labelValues(MlFeatureRow row) {
        if (row.getLabelValuesJson() == null || row.getLabelValuesJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.getLabelValuesJson(), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse feature row label values", exception);
        }
    }

    private Map<String, Object> smokeMetrics(List<MlFeatureRow> featureRows) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (MlFeatureRow row : featureRows) {
            Map<String, Double> numeric = numericFeatures(row);
            if (!numeric.isEmpty()) {
                metrics.putAll(numeric);
                break;
            }
        }
        return metrics;
    }

    private List<String> supportedMetrics(UUID featureSnapshotId) {
        return featureRowRepository.findByFeatureSnapshotIdOrderByPredictionAtAsc(featureSnapshotId).stream()
                .flatMap(row -> numericFeatures(row).keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    private String featureContractMetadataHash(MlFeatureSnapshot featureSnapshot) {
        String modelFamily = featureSnapshot.getModelFamily() == null || featureSnapshot.getModelFamily().isBlank()
                ? "ANOMALY_DETECTION"
                : featureSnapshot.getModelFamily();
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                modelFamily,
                featureSnapshot.getHorizonDays(),
                featureSnapshot.getFeatureTemplateId(),
                featureSnapshot.getFeatureTemplateVersion(),
                enabledFeatureFamilies(featureSnapshot));
        return FeatureVectorContract.contractMetadataHash(
                featureSnapshot.getFeatureSetVersion(),
                featureSnapshot.getCodeConfigVersion(),
                contract.contractVersion(),
                contract.modelFamily().name(),
                contract.horizonDays(),
                contract.featureTemplateId(),
                contract.featureTemplateVersion(),
                contract.requiredColumns());
    }

    private List<String> enabledFeatureFamilies(MlFeatureSnapshot featureSnapshot) {
        if (featureSnapshot.getFeatureConfigJson() == null || featureSnapshot.getFeatureConfigJson().isBlank()) {
            return List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context");
        }
        Map<String, Object> config = readJson(featureSnapshot.getFeatureConfigJson());
        Object families = config.get("enabledFeatureFamilies");
        if (!(families instanceof List<?> list) || list.isEmpty()) {
            return List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context");
        }
        return list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
    }

    private String modelType(MlTrainingRun run) {
        return run.getModelFamily() == MlModelFamily.FAILURE_PREDICTION
                ? "trained_failure_prediction"
                : "trained_tabular_anomaly";
    }

    private Integer integerOption(String hyperparametersJson, String key) {
        Object value = hyperparameterValue(hyperparametersJson, key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double doubleOption(String hyperparametersJson, String key) {
        Object value = hyperparameterValue(hyperparametersJson, key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOption(String hyperparametersJson, String key) {
        Object value = hyperparameterValue(hyperparametersJson, key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private Object hyperparameterValue(String hyperparametersJson, String key) {
        if (hyperparametersJson == null || hyperparametersJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = objectMapper.readValue(hyperparametersJson, new TypeReference<>() {});
            return values.get(key);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String stableVirtualDeviceId(MlFeatureRow row) {
        return UUID.nameUUIDFromBytes(row.getEntityId().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String publicReason(Exception exception) {
        String message = exception.getMessage();
        if (exception instanceof IllegalStateException && Set.of(
                "Dataset version is not READY",
                "Feature snapshot has no rows",
                "Feature snapshot has no numeric feature rows",
                "ML-service training did not produce a succeeded model artifact",
                "ML-service model artifact failed load-test inference",
                "Could not parse feature row values",
                "Could not parse feature row label values",
                "Could not serialize training metrics").contains(message == null ? "" : message)) return message;
        return "Training failed before model artifact registration.";
    }

    private String idempotencyKey(MlTrainingRun run) {
        return "ml-training:" + run.getId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize training metrics");
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static class TrainingCanceledException extends RuntimeException {
    }
}
