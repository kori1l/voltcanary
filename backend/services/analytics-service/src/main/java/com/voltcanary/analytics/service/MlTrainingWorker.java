package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
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
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "app.ml.training.worker-enabled", havingValue = "true")
public class MlTrainingWorker {

    private static final Logger log = LoggerFactory.getLogger(MlTrainingWorker.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(15);

    private final MlTrainingRunRepository trainingRunRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureRowRepository featureRowRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlClient mlClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    public MlTrainingWorker(
            MlTrainingRunRepository trainingRunRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlFeatureRowRepository featureRowRepository,
            MlModelVersionRepository modelVersionRepository,
            MlClient mlClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.trainingRunRepository = trainingRunRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.featureRowRepository = featureRowRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.mlClient = mlClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.ml.training.worker-delay:5000}")
    public void scheduledExecuteNextQueuedRun() {
        executeNextQueuedRun();
    }

    public Optional<MlTrainingRun> executeNextQueuedRun() {
        recoverStaleRuns();
        return trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED).stream()
                .findFirst()
                .map(this::claim)
                .map(this::execute);
    }

    MlTrainingRun execute(MlTrainingRun run) {
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
                    datasetVersion.getId(),
                    run.getAccountId(),
                    datasetVersion.getSourceType().name(),
                    run.getModelFamily().name(),
                    run.getAlgorithm(),
                    integerOption(run.getHyperparametersJson(), "failureHorizonDays"),
                    integerOption(run.getHyperparametersJson(), "minimumPositiveLabels"),
                    doubleOption(run.getHyperparametersJson(), "minimumLabelCoverage"),
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
            metrics.put("trainingFeatureValues", trainingRows.size());
            metrics.put("rejectedFeatureRows", featureSnapshot.getRejectedRowCount());
            metrics.put("loadTestPredictionStatus", smokeResponse.predictionStatus());
            String metricsJson = writeJson(metrics);

            run.setMetricsJson(metricsJson);
            run.setArtifactUri(trainingResponse.artifactUri());
            run.setLogsSummary("Training delegated to ML-service and load-test inference passed.");
            run.setStatus(MlTrainingRunStatus.SUCCEEDED);
            run.setCompletedAt(Instant.now());
            clearLease(run);
            trainingRunRepository.save(run);
            registerCandidateModel(run, datasetVersion, featureSnapshot, metricsJson, trainingResponse, modelVersionId);
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "succeeded", "algorithm", run.getAlgorithm()));
            log.info("mlTrainingSucceeded runId={} accountId={} durationMs={} artifactUri={} trainingRows={}",
                    run.getId(), run.getAccountId(), java.time.Duration.between(started, run.getCompletedAt()).toMillis(), trainingResponse.artifactUri(), trainingRows.size());
            return run;
        } catch (TrainingCanceledException exception) {
            run.setStatus(MlTrainingRunStatus.CANCELED);
            run.setFailureReason(null);
            run.setLogsSummary("Training canceled before candidate model registration.");
            run.setCompletedAt(Instant.now());
            clearLease(run);
            trainingRunRepository.save(run);
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "canceled", "algorithm", run.getAlgorithm()));
            log.info("mlTrainingCanceled runId={} accountId={} durationMs={}",
                    run.getId(), run.getAccountId(), java.time.Duration.between(started, run.getCompletedAt()).toMillis());
            return run;
        } catch (Exception exception) {
            run.setStatus(MlTrainingRunStatus.FAILED);
            run.setFailureReason(publicReason(exception));
            run.setLogsSummary("Training failed before model artifact registration.");
            run.setCompletedAt(Instant.now());
            clearLease(run);
            trainingRunRepository.save(run);
            meterRegistry.counter("analytics.ml.training.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.ml.training.duration", "status", "failed", "algorithm", run.getAlgorithm()));
            log.warn("mlTrainingFailed runId={} accountId={} durationMs={} reason={}",
                    run.getId(), run.getAccountId(), java.time.Duration.between(started, run.getCompletedAt()).toMillis(), run.getFailureReason());
            return run;
        }
    }

    private MlTrainingRun claim(MlTrainingRun run) {
        Instant now = Instant.now();
        run.setStatus(MlTrainingRunStatus.RUNNING);
        run.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        run.setLeaseOwner(workerId);
        run.setLeaseExpiresAt(now.plus(LEASE_DURATION));
        run.setHeartbeatAt(now);
        MlTrainingRun saved = trainingRunRepository.save(run);
        return saved == null ? run : saved;
    }

    void recoverStaleRuns() {
        Instant now = Instant.now();
        List<MlTrainingRun> staleRuns = trainingRunRepository.findByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(MlTrainingRunStatus.RUNNING, now);
        if (staleRuns == null) {
            return;
        }
        for (MlTrainingRun stale : staleRuns) {
            stale.setStatus(MlTrainingRunStatus.QUEUED);
            stale.setLeaseOwner(null);
            stale.setLeaseExpiresAt(null);
            stale.setHeartbeatAt(null);
            stale.setRetryCount(stale.getRetryCount() + 1);
            stale.setStaleRecoveredAt(now);
            stale.setLogsSummary("Training run recovered from stale worker lease.");
            trainingRunRepository.save(stale);
            meterRegistry.counter("analytics.ml.training.stale.recovered").increment();
            log.warn("mlTrainingStaleRecovered runId={} accountId={} retryCount={}", stale.getId(), stale.getAccountId(), stale.getRetryCount());
        }
    }

    private void heartbeat(MlTrainingRun run) {
        Instant now = Instant.now();
        run.setHeartbeatAt(now);
        run.setLeaseExpiresAt(now.plus(LEASE_DURATION));
        trainingRunRepository.save(run);
    }

    private void assertNotCanceled(MlTrainingRun run) {
        MlTrainingRun latest = trainingRunRepository.findById(run.getId()).orElse(run);
        if (latest.isCancellationRequested() || latest.getStatus() == MlTrainingRunStatus.CANCELED) {
            run.setCancellationRequested(true);
            throw new TrainingCanceledException();
        }
    }

    private void clearLease(MlTrainingRun run) {
        run.setLeaseOwner(null);
        run.setLeaseExpiresAt(null);
        run.setHeartbeatAt(null);
    }

    private void registerCandidateModel(
            MlTrainingRun run,
            MlDatasetVersion datasetVersion,
            MlFeatureSnapshot featureSnapshot,
            String metricsJson,
            MlTrainingResponse trainingResponse,
            UUID modelVersionId) {
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

    private String modelType(MlTrainingRun run) {
        return run.getModelFamily() == com.voltcanary.analytics.domain.MlModelFamily.FAILURE_PREDICTION
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
        if (message == null || message.isBlank()) {
            return "Training failed";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize training metrics");
        }
    }

    private static class TrainingCanceledException extends RuntimeException {
    }
}
