package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlTrainingRunDto;
import com.voltcanary.analytics.dto.MlTrainingRunRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlInferenceResponse;
import com.voltcanary.analytics.ml.MlTrainingRequest;
import com.voltcanary.analytics.ml.MlTrainingResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import com.voltcanary.analytics.service.MlTrainingService;
import com.voltcanary.analytics.service.MlTrainingWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlTrainingLifecycleTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlFeatureSnapshotRepository featureSnapshotRepository = mock(MlFeatureSnapshotRepository.class);
    private final MlFeatureRowRepository featureRowRepository = mock(MlFeatureRowRepository.class);
    private final MlTrainingRunRepository trainingRunRepository = mock(MlTrainingRunRepository.class);
    private final MlModelVersionRepository modelVersionRepository = mock(MlModelVersionRepository.class);
    private final MlClient mlClient = mock(MlClient.class);
    private final MlTrainingService service = new MlTrainingService(
            datasetVersionRepository,
            featureSnapshotRepository,
            trainingRunRepository,
            modelVersionRepository);
    private final MlTrainingWorker worker = new MlTrainingWorker(
            trainingRunRepository,
            datasetVersionRepository,
            featureSnapshotRepository,
            featureRowRepository,
            modelVersionRepository,
            mlClient,
            new ObjectMapper(),
            new SimpleMeterRegistry());

    @Test
    void createsQueuedAnomalyTrainingRun() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 10)));

        MlTrainingRunDto response = service.create(accountId, userId, new MlTrainingRunRequest(
                datasetVersionId,
                featureSnapshotId,
                MlModelFamily.ANOMALY_DETECTION,
                null,
                null));

        assertThat(response.status()).isEqualTo(MlTrainingRunStatus.QUEUED);
        assertThat(response.algorithm()).isEqualTo("sklearn-isolation-forest-v1");
    }

    @Test
    void archivedFeatureSnapshotsAreRejectedByDefaultTrainingSelector() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlFeatureSnapshot snapshot = featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 10);
        snapshot.setLifecycleStatus("ARCHIVED");
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.create(accountId, userId, new MlTrainingRunRequest(
                datasetVersionId,
                featureSnapshotId,
                MlModelFamily.ANOMALY_DETECTION,
                null,
                null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Feature snapshot must be active");
    }

    @Test
    void createsQueuedFailurePredictionTrainingRun() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlFeatureSnapshot snapshot = featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 10);
        snapshot.setModelFamily("FAILURE_PREDICTION");
        snapshot.setHorizonDays(14);
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(snapshot));

        MlTrainingRunDto response = service.create(accountId, userId, new MlTrainingRunRequest(
                datasetVersionId,
                featureSnapshotId,
                MlModelFamily.FAILURE_PREDICTION,
                null,
                14,
                1,
                0.5,
                "{}"));

        assertThat(response.status()).isEqualTo(MlTrainingRunStatus.QUEUED);
        assertThat(response.algorithm()).isEqualTo("sklearn-logistic-regression-failure-v1");
        assertThat(response.hyperparametersJson())
                .contains("\"failureHorizonDays\":14")
                .contains("\"minimumPositiveLabels\":1")
                .contains("\"minimumLabelCoverage\":0.5");
    }

    @Test
    void workerCompletesQueuedRunAndRegistersCandidateModel() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        UUID modelVersionId = UUID.randomUUID();
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 42)));
        when(featureRowRepository.findByFeatureSnapshotIdOrderByPredictionAtAsc(featureSnapshotId)).thenReturn(List.of(featureRow(accountId, datasetVersionId, featureSnapshotId)));
        when(mlClient.requestTraining(org.mockito.ArgumentMatchers.any(MlTrainingRequest.class))).thenReturn(new MlTrainingResponse(
                UUID.randomUUID().toString(),
                "SUCCEEDED",
                datasetVersionId.toString(),
                modelVersionId.toString(),
                "sklearn-isolation-forest-v1-test",
                "artifact://local/model/model.joblib",
                "artifact://local/model/manifest.json",
                "sha256:model-artifact",
                2048L,
                Map.of("trainingRows", 4.0)));
        when(mlClient.requestPrediction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new MlInferenceResponse(
                        UUID.randomUUID(),
                        modelVersionId.toString(),
                        "trained-model",
                        "sklearn-isolation-forest-v1-test",
                        "trained_tabular_anomaly",
                        "READY",
                        "TRAINED_MODEL",
                        0.2,
                        0.1,
                        "LOW",
                        0.8,
                        List.of(),
                        List.of("ok"),
                        List.of(),
                        "hash"));
        when(modelVersionRepository.findByModelKeyOrderByVersionDesc("account-" + accountId + "-anomaly_detection")).thenReturn(List.of());

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MlTrainingRunStatus.SUCCEEDED);
        assertThat(result.get().getLeaseOwner()).isNull();
        assertThat(result.get().getLeaseExpiresAt()).isNull();
        assertThat(result.get().getHeartbeatAt()).isNull();
        assertThat(result.get().getMetricsJson()).contains("trainingRows");
        assertThat(result.get().getArtifactUri()).isEqualTo("artifact://local/model/model.joblib");

        ArgumentCaptor<Map<String, Object>> smokePreference = ArgumentCaptor.forClass(Map.class);
        verify(mlClient).requestPrediction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                smokePreference.capture());
        assertThat(smokePreference.getValue())
                .containsEntry("modelVersionId", modelVersionId.toString())
                .containsEntry("artifactUri", "artifact://local/model/model.joblib")
                .containsEntry("manifestUri", "artifact://local/model/manifest.json")
                .containsEntry("artifactHash", "sha256:model-artifact")
                .containsEntry("artifactSizeBytes", 2048L)
                .containsEntry("allowBaselineHeuristic", false);

        ArgumentCaptor<MlModelVersion> model = ArgumentCaptor.forClass(MlModelVersion.class);
        verify(modelVersionRepository).save(model.capture());
        assertThat(model.getValue().getId()).isEqualTo(modelVersionId);
        assertThat(model.getValue().getTrainingRunId()).isEqualTo(run.getId());
        assertThat(model.getValue().getVersion()).isEqualTo(1);
        assertThat(model.getValue().getFeatureSchemaHash()).isEqualTo("sha256:test-feature-schema");
        assertThat(model.getValue().getArtifactManifestUri()).isEqualTo("artifact://local/model/manifest.json");
        assertThat(model.getValue().getArtifactHash()).isEqualTo("sha256:model-artifact");
        assertThat(model.getValue().getArtifactSizeBytes()).isEqualTo(2048L);
        assertThat(model.getValue().getSupportedMetricsJson()).contains("activePowerW", "current", "voltage");
    }

    @Test
    void workerMarksRunFailedForInvalidDatasetFeatureCombination() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 0)));

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MlTrainingRunStatus.FAILED);
        assertThat(result.get().getFailureReason()).contains("no rows");
    }

    @Test
    void workerDoesNotRegisterCandidateWhenLoadTestInferenceFails() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        UUID modelVersionId = UUID.randomUUID();
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 42)));
        when(featureRowRepository.findByFeatureSnapshotIdOrderByPredictionAtAsc(featureSnapshotId)).thenReturn(List.of(featureRow(accountId, datasetVersionId, featureSnapshotId)));
        when(mlClient.requestTraining(org.mockito.ArgumentMatchers.any(MlTrainingRequest.class))).thenReturn(new MlTrainingResponse(
                UUID.randomUUID().toString(),
                "SUCCEEDED",
                datasetVersionId.toString(),
                modelVersionId.toString(),
                "sklearn-isolation-forest-v1-test",
                "artifact://local/model/model.joblib",
                "artifact://local/model/manifest.json",
                "sha256:model-artifact",
                2048L,
                Map.of("trainingRows", 4.0)));
        when(mlClient.requestPrediction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new MlInferenceResponse(
                        UUID.randomUUID(),
                        modelVersionId.toString(),
                        "trained-model",
                        "sklearn-isolation-forest-v1-test",
                        "trained_tabular_anomaly",
                        "READY",
                        "MODEL_UNAVAILABLE",
                        0.0,
                        0.0,
                        "LOW",
                        0.1,
                        List.of(),
                        List.of("missing"),
                        List.of(),
                        "hash"));

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MlTrainingRunStatus.FAILED);
        assertThat(result.get().getFailureReason()).contains("load-test inference");
        verify(modelVersionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workerPassesFailureHorizonLabelsToMlServiceRows() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        run.setModelFamily(MlModelFamily.FAILURE_PREDICTION);
        run.setAlgorithm("sklearn-logistic-regression-failure-v1");
        run.setHyperparametersJson("{\"failureHorizonDays\":14,\"minimumPositiveLabels\":1,\"minimumLabelCoverage\":0.5}");
        UUID modelVersionId = UUID.randomUUID();
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 42)));
        when(featureRowRepository.findByFeatureSnapshotIdOrderByPredictionAtAsc(featureSnapshotId))
                .thenReturn(List.of(labelledFeatureRow(accountId, datasetVersionId, featureSnapshotId)));
        when(mlClient.requestTraining(org.mockito.ArgumentMatchers.any(MlTrainingRequest.class))).thenReturn(new MlTrainingResponse(
                UUID.randomUUID().toString(),
                "SUCCEEDED",
                datasetVersionId.toString(),
                modelVersionId.toString(),
                "failure-v1-test",
                "artifact://local/model/model.joblib",
                "artifact://local/model/manifest.json",
                "sha256:model-artifact",
                2048L,
                Map.of("failurePositiveLabels", 1.0)));
        when(mlClient.requestPrediction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new MlInferenceResponse(
                        UUID.randomUUID(),
                        modelVersionId.toString(),
                        "trained-model",
                        "failure-v1-test",
                        "trained_failure_prediction",
                        "READY",
                        "TRAINED_MODEL",
                        0.0,
                        0.0,
                        "LOW",
                        0.8,
                        List.of(),
                        List.of("ok"),
                        List.of(),
                        "hash"));
        when(modelVersionRepository.findByModelKeyOrderByVersionDesc("account-" + accountId + "-failure_prediction")).thenReturn(List.of());

        worker.executeNextQueuedRun();

        ArgumentCaptor<MlTrainingRequest> request = ArgumentCaptor.forClass(MlTrainingRequest.class);
        verify(mlClient).requestTraining(request.capture());
        assertThat(request.getValue().modelFamily()).isEqualTo("FAILURE_PREDICTION");
        assertThat(request.getValue().failureHorizonDays()).isEqualTo(14);
        assertThat(request.getValue().rows())
                .allSatisfy(row -> {
                    assertThat(row.label()).isEqualTo("CONFIRMED_FAILURE");
                    assertThat(row.metadata())
                            .containsEntry("failureWithinHorizon", true)
                            .containsEntry("failureOutcomeType", "CONFIRMED_FAILURE")
                            .containsEntry("horizonDays", 14);
                });
        ArgumentCaptor<Map<String, Object>> smokeMetrics = ArgumentCaptor.forClass(Map.class);
        verify(mlClient).requestPrediction(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                smokeMetrics.capture(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(smokeMetrics.getValue())
                .containsEntry("applianceLoadRatio", 0.92)
                .containsEntry("dayOfWeek", 2.0)
                .containsEntry("hourOfDay", 10.0);
    }

    @Test
    void cancelAndRetryTrainingRuns() {
        UUID accountId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, UUID.randomUUID(), UUID.randomUUID());
        when(trainingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        MlTrainingRunDto canceled = service.cancel(accountId, run.getId());
        assertThat(canceled.status()).isEqualTo(MlTrainingRunStatus.CANCELED);

        MlTrainingRunDto retry = service.retry(accountId, run.getId());
        assertThat(retry.status()).isEqualTo(MlTrainingRunStatus.QUEUED);
        assertThat(retry.id()).isNotEqualTo(run.getId());
        assertThat(retry.retryCount()).isEqualTo(1);
    }

    @Test
    void blocksDeletingTrainingRunWithRegisteredModel() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, UUID.randomUUID(), UUID.randomUUID());
        run.setStatus(MlTrainingRunStatus.SUCCEEDED);
        when(trainingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(modelVersionRepository.countByTrainingRunId(run.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(accountId, actorId, run.getId(), new MlLifecycleActionRequest("cleanup", false)))
                .isInstanceOf(MlLifecycleConflictException.class)
                .hasMessageContaining("model lineage");
    }

    @Test
    void cancelRunningRunRequestsCooperativeCancellation() {
        UUID accountId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, UUID.randomUUID(), UUID.randomUUID());
        run.setStatus(MlTrainingRunStatus.RUNNING);
        when(trainingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        MlTrainingRunDto canceled = service.cancel(accountId, run.getId());

        assertThat(canceled.status()).isEqualTo(MlTrainingRunStatus.RUNNING);
        assertThat(canceled.cancellationRequested()).isTrue();
        assertThat(canceled.completedAt()).isNull();
    }

    @Test
    void workerCancelsBeforeRegisteringCandidateWhenCancellationIsObserved() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        run.setCancellationRequested(true);
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(trainingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MlTrainingRunStatus.CANCELED);
        assertThat(result.get().getCompletedAt()).isNotNull();
        assertThat(result.get().getLeaseOwner()).isNull();
        verify(modelVersionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(mlClient, never()).requestTraining(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workerRecoversStaleRunningRunToQueue() {
        UUID accountId = UUID.randomUUID();
        MlTrainingRun stale = queuedRun(accountId, UUID.randomUUID(), UUID.randomUUID());
        stale.setStatus(MlTrainingRunStatus.RUNNING);
        stale.setLeaseOwner("dead-worker");
        stale.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        stale.setHeartbeatAt(Instant.now().minusSeconds(120));
        when(trainingRunRepository.findByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.eq(MlTrainingRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(stale));
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of());

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isEmpty();
        assertThat(stale.getStatus()).isEqualTo(MlTrainingRunStatus.QUEUED);
        assertThat(stale.getLeaseOwner()).isNull();
        assertThat(stale.getRetryCount()).isEqualTo(1);
        assertThat(stale.getStaleRecoveredAt()).isNotNull();
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID id) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(id);
        version.setDatasetId(UUID.randomUUID());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        version.setStatus(MlDatasetStatus.READY);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("feature-config-v1");
        return version;
    }

    private MlFeatureSnapshot featureSnapshot(UUID accountId, UUID datasetVersionId, UUID id, long rows) {
        MlFeatureSnapshot snapshot = new MlFeatureSnapshot();
        snapshot.setId(id);
        snapshot.setAccountId(accountId);
        snapshot.setDatasetVersionId(datasetVersionId);
        snapshot.setFeatureSchemaHash("sha256:test-feature-schema");
        snapshot.setFeatureSetVersion("feature-set-v1");
        snapshot.setCodeConfigVersion("pipeline-v1");
        snapshot.setFeatureConfigJson("{}");
        snapshot.setRowCount(rows);
        snapshot.setRejectedRowCount(0);
        snapshot.setCreatedAt(Instant.now());
        return snapshot;
    }

    private MlFeatureRow featureRow(UUID accountId, UUID datasetVersionId, UUID featureSnapshotId) {
        MlFeatureRow row = new MlFeatureRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(accountId);
        row.setDatasetVersionId(datasetVersionId);
        row.setFeatureSnapshotId(featureSnapshotId);
        row.setEntityId("device-1");
        row.setDeviceId(UUID.randomUUID());
        row.setDeviceType("meter");
        row.setPredictionAt(Instant.now());
        row.setFeatureValuesJson("""
                {"voltage":230.0,"current":5.2,"activePowerW":840.0}
                """);
        row.setSourceLineageJson("{}");
        row.setQualityFlagsJson("[]");
        row.setCreatedAt(Instant.now());
        return row;
    }

    private MlFeatureRow labelledFeatureRow(UUID accountId, UUID datasetVersionId, UUID featureSnapshotId) {
        MlFeatureRow row = featureRow(accountId, datasetVersionId, featureSnapshotId);
        row.setHorizonDays(14);
        row.setFeatureValuesJson("""
                {"rollingMean":830.0,"rollingMin":805.0,"rollingMax":910.0,"rollingStddev":12.5,"baselineDeviationRatio":1.18,"samplingGapSeconds":14400,"hourOfDay":10,"dayOfWeek":2,"applianceLoadRatio":0.92}
                """);
        row.setLabelValuesJson("""
                {"label":"CONFIRMED_FAILURE","failureWithinHorizon":true,"failureOutcomeType":"CONFIRMED_FAILURE","horizonDays":14}
                """);
        return row;
    }

    private MlTrainingRun queuedRun(UUID accountId, UUID datasetVersionId, UUID featureSnapshotId) {
        MlTrainingRun run = new MlTrainingRun();
        run.setId(UUID.randomUUID());
        run.setAccountId(accountId);
        run.setDatasetVersionId(datasetVersionId);
        run.setFeatureSnapshotId(featureSnapshotId);
        run.setModelFamily(MlModelFamily.ANOMALY_DETECTION);
        run.setAlgorithm("sklearn-isolation-forest-v1");
        run.setHyperparametersJson("{}");
        run.setStatus(MlTrainingRunStatus.QUEUED);
        run.setCreatedBy(UUID.randomUUID());
        run.setCreatedAt(Instant.now());
        return run;
    }
}
