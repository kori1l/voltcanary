package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationRequest;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlHorizonLabelGenerationJobRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.analytics.service.MlHorizonLabelGenerationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MlHorizonLabelGenerationServiceTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository = mock(MlCanonicalMetricRowRepository.class);
    private final MaintenanceOutcomeReadModelRepository outcomeRepository = mock(MaintenanceOutcomeReadModelRepository.class);
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService = mock(MaintenanceWindowProjectionService.class);
    private final MlLabelRepository labelRepository = mock(MlLabelRepository.class);
    private final MlHorizonLabelGenerationJobRepository jobRepository = mock(MlHorizonLabelGenerationJobRepository.class);
    private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };
    private final MlHorizonLabelGenerationService service = new MlHorizonLabelGenerationService(
            datasetVersionRepository,
            canonicalMetricRowRepository,
            outcomeRepository,
            maintenanceWindowProjectionService,
            labelRepository,
            jobRepository,
            new SyncTaskExecutor(),
            transactionManager);

    @Test
    void generatesAuditableHorizonLabelsFromOutcomes() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID positiveDevice = UUID.randomUUID();
        UUID maintenanceDevice = UUID.randomUUID();
        UUID normalDevice = UUID.randomUUID();
        UUID unresolvedDevice = UUID.randomUUID();
        UUID recentDevice = UUID.randomUUID();
        UUID shortHistoryDevice = UUID.randomUUID();
        Instant base = Instant.parse("2026-07-01T00:00:00Z");
        MlDatasetVersion datasetVersion = datasetVersion(accountId, datasetVersionId);
        List<MlCanonicalMetricRow> rows = List.of(
                row(datasetVersionId, positiveDevice, base),
                row(datasetVersionId, maintenanceDevice, base),
                row(datasetVersionId, normalDevice, base),
                row(datasetVersionId, normalDevice, base.plus(8, java.time.temporal.ChronoUnit.DAYS)),
                row(datasetVersionId, unresolvedDevice, base),
                row(datasetVersionId, shortHistoryDevice, base),
                row(datasetVersionId, recentDevice, base.plus(18, java.time.temporal.ChronoUnit.DAYS)));
        UUID failureOutcomeId = UUID.randomUUID();
        UUID unresolvedOutcomeId = UUID.randomUUID();

        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersionId)).thenReturn(rows);
        when(outcomeRepository.findByAccountAndObservedRange(eq(accountId), any(), any())).thenReturn(List.of(
                outcome(accountId, positiveDevice, failureOutcomeId, base.plus(2, java.time.temporal.ChronoUnit.DAYS), "CONFIRMED_FAILURE"),
                outcome(accountId, unresolvedDevice, unresolvedOutcomeId, base.plus(1, java.time.temporal.ChronoUnit.DAYS), "UNRESOLVED_ANOMALY")));
        when(maintenanceWindowProjectionService.activeOverlapping(any(), any(), any())).thenAnswer(invocation -> {
            UUID deviceId = invocation.getArgument(0);
            if (!maintenanceDevice.equals(deviceId)) {
                return List.of();
            }
            MaintenanceWindowReadModel window = new MaintenanceWindowReadModel();
            window.setMaintenanceWindowId(UUID.randomUUID());
            window.setDeviceId(maintenanceDevice);
            window.setExcludeFromMl(true);
            return List.of(window);
        });

        UUID jobId = UUID.randomUUID();
        MlHorizonLabelGenerationJob job = job(accountId, actorId, datasetVersionId, jobId, "test-v1", 7, false);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.runJob(jobId);

        assertThat(job.getStatus()).isEqualTo(MlHorizonLabelGenerationStatus.COMPLETED);
        assertThat(job.getGeneratedLabels()).isEqualTo(4);
        assertThat(job.getPositiveLabels()).isEqualTo(1);
        assertThat(job.getNormalLabels()).isEqualTo(1);
        assertThat(job.getMaintenanceExcludedLabels()).isEqualTo(1);
        assertThat(job.getUnresolvedLabels()).isEqualTo(1);
        assertThat(job.getUnobservableRows()).isEqualTo(3);

        ArgumentCaptor<Iterable<MlLabel>> labelsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(labelRepository).saveAll(labelsCaptor.capture());
        List<MlLabel> labels = new ArrayList<>();
        labelsCaptor.getValue().forEach(labels::add);
        assertThat(labels).extracting(MlLabel::getLabel)
                .containsExactlyInAnyOrder(
                        MlLabelType.CONFIRMED_FAILURE,
                        MlLabelType.MAINTENANCE,
                        MlLabelType.NORMAL,
                        MlLabelType.UNRESOLVED_ANOMALY);
        assertThat(labels).allSatisfy(label -> {
            assertThat(label.getHorizonDays()).isEqualTo(7);
            assertThat(label.getGenerationVersion()).isEqualTo("test-v1");
            assertThat(label.getLineageHash()).isEqualTo(job.getLineageHash());
        });
        assertThat(labels.stream()
                .filter(label -> label.getLabel() == MlLabelType.CONFIRMED_FAILURE)
                .findFirst()
                .orElseThrow()
                .getSourceOutcomeIdsJson()).contains(failureOutcomeId.toString());
    }

    @Test
    void startReturnsExistingActiveOrCompletedJobForSameIdempotencyKey() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        Instant base = Instant.parse("2026-07-01T00:00:00Z");
        MlDatasetVersion datasetVersion = datasetVersion(accountId, datasetVersionId);
        datasetVersion.setDataHash("sha256:dataset");
        MlCanonicalMetricRow row = row(datasetVersionId, UUID.randomUUID(), base);
        MlHorizonLabelGenerationJob existing = job(accountId, actorId, datasetVersionId, UUID.randomUUID(), "test-v1", 7, false);
        existing.setStatus(MlHorizonLabelGenerationStatus.RUNNING);

        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersionId)).thenReturn(List.of(row));
        when(jobRepository.findFirstByAccountIdAndIdempotencyKeyAndStatusInOrderBySubmittedAtDesc(eq(accountId), any(), any()))
                .thenReturn(Optional.of(existing));

        var response = service.start(accountId, actorId, new MlHorizonLabelGenerationRequest(datasetVersionId, 7, "test-v1", false));

        assertThat(response.jobId()).isEqualTo(existing.getId());
        assertThat(response.status()).isEqualTo(MlHorizonLabelGenerationStatus.RUNNING);
    }

    @Test
    void runJobMarksFailedWithSanitizedReasonWhenGenerationFailsAfterAcceptance() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        MlDatasetVersion datasetVersion = datasetVersion(accountId, datasetVersionId);
        MlHorizonLabelGenerationJob job = job(accountId, actorId, datasetVersionId, jobId, "test-v1", 7, false);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(datasetVersionRepository.findById(datasetVersionId)).thenReturn(Optional.of(datasetVersion));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersionId)).thenReturn(List.of());

        service.runJob(jobId);

        assertThat(job.getStatus()).isEqualTo(MlHorizonLabelGenerationStatus.FAILED);
        assertThat(job.getFailureReason()).isEqualTo("Dataset version has no canonical rows");
        assertThat(job.getCompletedAt()).isNotNull();
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(datasetVersionId);
        version.setDatasetId(UUID.randomUUID());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(MlDatasetStatus.READY);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("test");
        version.setCreatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        return version;
    }

    private MlHorizonLabelGenerationJob job(
            UUID accountId,
            UUID actorId,
            UUID datasetVersionId,
            UUID jobId,
            String generationVersion,
            int horizonDays,
            boolean includePreventiveMaintenance) {
        MlHorizonLabelGenerationJob job = new MlHorizonLabelGenerationJob();
        job.setId(jobId);
        job.setAccountId(accountId);
        job.setDatasetVersionId(datasetVersionId);
        job.setSubmittedBy(actorId);
        job.setHorizonDays(horizonDays);
        job.setGenerationVersion(generationVersion);
        job.setIncludePreventiveMaintenance(includePreventiveMaintenance);
        job.setInputLineageHash("sha256:dataset");
        job.setIdempotencyKey("sha256:idempotency");
        job.setStatus(MlHorizonLabelGenerationStatus.QUEUED);
        job.setSubmittedAt(Instant.parse("2026-07-01T00:00:00Z"));
        return job;
    }

    private MlCanonicalMetricRow row(UUID datasetVersionId, UUID deviceId, Instant timestamp) {
        MlCanonicalMetricRow row = new MlCanonicalMetricRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(UUID.randomUUID());
        row.setDatasetVersionId(datasetVersionId);
        row.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        row.setDeviceId(deviceId);
        row.setMetricName("activePowerW");
        row.setCanonicalMetricName("active_power");
        row.setTimestamp(timestamp);
        row.setNumericValue(100.0);
        row.setQualityFlags("[]");
        row.setMetadataJson("{}");
        row.setSourceLineage("{}");
        row.setCreatedAt(timestamp);
        return row;
    }

    private MaintenanceOutcomeReadModel outcome(
            UUID accountId,
            UUID deviceId,
            UUID outcomeId,
            Instant observedAt,
            String outcomeType) {
        MaintenanceOutcomeReadModel outcome = new MaintenanceOutcomeReadModel();
        outcome.setMaintenanceOutcomeId(outcomeId);
        outcome.setAccountId(accountId);
        outcome.setDeviceId(deviceId);
        outcome.setObservedAt(observedAt);
        outcome.setOutcomeType(outcomeType);
        outcome.setSource("TECHNICIAN");
        outcome.setConfidence(1.0);
        outcome.setVersion(1);
        outcome.setUpdatedAt(observedAt);
        return outcome;
    }
}
