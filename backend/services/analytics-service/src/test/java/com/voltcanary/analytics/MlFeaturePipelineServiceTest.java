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
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.service.MlFeaturePipelineService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlFeaturePipelineServiceTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlImportedFileRepository importedFileRepository = mock(MlImportedFileRepository.class);
    private final MlImportedMetricRowRepository rowRepository = mock(MlImportedMetricRowRepository.class);
    private final MlFeatureSnapshotRepository snapshotRepository = mock(MlFeatureSnapshotRepository.class);
    private final MlFeaturePipelineService service = new MlFeaturePipelineService(
            datasetVersionRepository,
            importedFileRepository,
            rowRepository,
            snapshotRepository,
            new ObjectMapper(),
            new SimpleMeterRegistry());

    @Test
    void sameDatasetAndFeatureConfigProduceSameFeatureSchemaHash() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId)));
        when(importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetId)).thenReturn(List.of(importedFile(fileId, datasetId)));
        when(rowRepository.findByImportedFileIdOrderBySourceRowNumber(fileId)).thenReturn(rows(accountId, fileId));

        MlFeatureSnapshotRequest request = new MlFeatureSnapshotRequest(
                versionId,
                "feature-set-v1",
                "pipeline-v1",
                2,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "label"));

        MlFeatureSnapshotResponse first = service.generate(accountId, request);
        MlFeatureSnapshotResponse second = service.generate(accountId, request);

        assertThat(first.featureSchemaHash()).isEqualTo(second.featureSchemaHash());
        assertThat(first.rowCount()).isEqualTo(3);
        assertThat(first.rejectedRowCount()).isZero();
    }

    @Test
    void storesFeatureSnapshotMetadataForGeneratedRows() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId)));
        when(importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetId)).thenReturn(List.of(importedFile(fileId, datasetId)));
        when(rowRepository.findByImportedFileIdOrderBySourceRowNumber(fileId)).thenReturn(rows(accountId, fileId));

        service.generate(accountId, new MlFeatureSnapshotRequest(versionId, null, null, 2, null, null));

        ArgumentCaptor<MlFeatureSnapshot> snapshot = ArgumentCaptor.forClass(MlFeatureSnapshot.class);
        verify(snapshotRepository).save(snapshot.capture());
        assertThat(snapshot.getValue().getFeatureConfigJson()).contains("rollingWindowSize");
        assertThat(snapshot.getValue().getFeatureSchemaHash()).startsWith("sha256:");
        assertThat(snapshot.getValue().getRowCount()).isEqualTo(3);
        assertThat(snapshot.getValue().getArtifactUri()).startsWith("db://ml_feature_snapshots/");
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetId, UUID versionId) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(versionId);
        version.setDatasetId(datasetId);
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.EXTERNAL_IMPORT);
        version.setStatus(MlDatasetStatus.READY);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("feature-config-v1");
        return version;
    }

    private MlImportedFile importedFile(UUID fileId, UUID datasetId) {
        MlImportedFile file = new MlImportedFile();
        file.setId(fileId);
        file.setDatasetId(datasetId);
        return file;
    }

    private List<MlImportedMetricRow> rows(UUID accountId, UUID fileId) {
        return List.of(
                row(accountId, fileId, 1, "washer-1", "activePowerW", "2026-07-01T00:00:00Z", 100.0),
                row(accountId, fileId, 2, "washer-1", "activePowerW", "2026-07-01T01:00:00Z", 120.0),
                row(accountId, fileId, 3, "washer-1", "activePowerW", "2026-07-01T02:00:00Z", 110.0));
    }

    private MlImportedMetricRow row(
            UUID accountId,
            UUID fileId,
            long sourceRowNumber,
            String deviceKey,
            String metric,
            String timestamp,
            double value) {
        MlImportedMetricRow row = new MlImportedMetricRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(accountId);
        row.setImportedFileId(fileId);
        row.setSourceRowNumber(sourceRowNumber);
        row.setExternalDeviceKey(deviceKey);
        row.setMetricName(metric);
        row.setTimestamp(Instant.parse(timestamp));
        row.setNumericValue(value);
        row.setMetadataJson("{}");
        row.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        return row;
    }
}
