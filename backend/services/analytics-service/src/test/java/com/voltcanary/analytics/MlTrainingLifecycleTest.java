package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlTrainingRunDto;
import com.voltcanary.analytics.dto.MlTrainingRunRequest;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.service.MlTrainingService;
import com.voltcanary.analytics.service.MlTrainingWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlTrainingLifecycleTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlFeatureSnapshotRepository featureSnapshotRepository = mock(MlFeatureSnapshotRepository.class);
    private final MlTrainingRunRepository trainingRunRepository = mock(MlTrainingRunRepository.class);
    private final MlModelVersionRepository modelVersionRepository = mock(MlModelVersionRepository.class);
    private final MlTrainingService service = new MlTrainingService(
            datasetVersionRepository,
            featureSnapshotRepository,
            trainingRunRepository);
    private final MlTrainingWorker worker = new MlTrainingWorker(
            trainingRunRepository,
            datasetVersionRepository,
            featureSnapshotRepository,
            modelVersionRepository,
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
        assertThat(response.algorithm()).isEqualTo("baseline-statistical-anomaly-v1");
    }

    @Test
    void workerCompletesQueuedRunAndRegistersCandidateModel() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID featureSnapshotId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, datasetVersionId, featureSnapshotId);
        when(trainingRunRepository.findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus.QUEUED)).thenReturn(List.of(run));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetVersionId)));
        when(featureSnapshotRepository.findById(featureSnapshotId)).thenReturn(Optional.of(featureSnapshot(accountId, datasetVersionId, featureSnapshotId, 42)));
        when(modelVersionRepository.findByModelKeyOrderByVersionDesc("account-" + accountId + "-anomaly_detection")).thenReturn(List.of());

        Optional<MlTrainingRun> result = worker.executeNextQueuedRun();

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MlTrainingRunStatus.SUCCEEDED);
        assertThat(result.get().getMetricsJson()).contains("trainingRows");
        assertThat(result.get().getArtifactUri()).startsWith("artifact://ml-models/");

        ArgumentCaptor<MlModelVersion> model = ArgumentCaptor.forClass(MlModelVersion.class);
        verify(modelVersionRepository).save(model.capture());
        assertThat(model.getValue().getTrainingRunId()).isEqualTo(run.getId());
        assertThat(model.getValue().getVersion()).isEqualTo(1);
        assertThat(model.getValue().getFeatureSchemaHash()).isEqualTo("sha256:test-feature-schema");
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
    void cancelAndRetryTrainingRuns() {
        UUID accountId = UUID.randomUUID();
        MlTrainingRun run = queuedRun(accountId, UUID.randomUUID(), UUID.randomUUID());
        when(trainingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        MlTrainingRunDto canceled = service.cancel(accountId, run.getId());
        assertThat(canceled.status()).isEqualTo(MlTrainingRunStatus.CANCELED);

        MlTrainingRunDto retry = service.retry(accountId, run.getId());
        assertThat(retry.status()).isEqualTo(MlTrainingRunStatus.QUEUED);
        assertThat(retry.id()).isNotEqualTo(run.getId());
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

    private MlTrainingRun queuedRun(UUID accountId, UUID datasetVersionId, UUID featureSnapshotId) {
        MlTrainingRun run = new MlTrainingRun();
        run.setId(UUID.randomUUID());
        run.setAccountId(accountId);
        run.setDatasetVersionId(datasetVersionId);
        run.setFeatureSnapshotId(featureSnapshotId);
        run.setModelFamily(MlModelFamily.ANOMALY_DETECTION);
        run.setAlgorithm("baseline-statistical-anomaly-v1");
        run.setHyperparametersJson("{}");
        run.setStatus(MlTrainingRunStatus.QUEUED);
        run.setCreatedBy(UUID.randomUUID());
        run.setCreatedAt(Instant.now());
        return run;
    }
}
