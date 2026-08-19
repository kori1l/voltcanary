package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.ml.FeatureTemplateCatalog;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.ml.MlInferenceRequest;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.service.MlFeaturePipelineService;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class MlFeaturePipelineServiceTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository = mock(MlCanonicalMetricRowRepository.class);
    private final MlImportedFileRepository importedFileRepository = mock(MlImportedFileRepository.class);
    private final MlImportedMetricRowRepository rowRepository = mock(MlImportedMetricRowRepository.class);
    private final MlFeatureSnapshotRepository snapshotRepository = mock(MlFeatureSnapshotRepository.class);
    private final MlFeatureRowRepository featureRowRepository = mock(MlFeatureRowRepository.class);
    private final MlLabelRepository labelRepository = mock(MlLabelRepository.class);
    private final MlTrainingRunRepository trainingRunRepository = mock(MlTrainingRunRepository.class);
    private final MlModelVersionRepository modelVersionRepository = mock(MlModelVersionRepository.class);
    private final MlFeaturePipelineService service = new MlFeaturePipelineService(
            datasetVersionRepository,
            canonicalMetricRowRepository,
            importedFileRepository,
            rowRepository,
            snapshotRepository,
            featureRowRepository,
            labelRepository,
            trainingRunRepository,
            modelVersionRepository,
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
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "label"),
                "ANOMALY_DETECTION",
                null,
                "generic-appliance",
                "template-v1");

        MlFeatureSnapshotResponse first = service.generate(accountId, request);
        MlFeatureSnapshotResponse second = service.generate(accountId, request);

        assertThat(first.featureSchemaHash()).isEqualTo(second.featureSchemaHash());
        assertThat(first.featureConfigHash()).isEqualTo(second.featureConfigHash());
        assertThat(first.featureDataHash()).isEqualTo(second.featureDataHash());
        assertThat(first.rowCount()).isEqualTo(3);
        assertThat(first.rejectedRowCount()).isZero();
    }

    @Test
    void blocksDeletingReferencedFeatureSnapshot() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        MlFeatureSnapshot snapshot = new MlFeatureSnapshot();
        snapshot.setId(snapshotId);
        snapshot.setAccountId(accountId);
        snapshot.setLifecycleStatus("ACTIVE");
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(trainingRunRepository.countByFeatureSnapshotId(snapshotId)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(accountId, actorId, snapshotId, new MlLifecycleActionRequest("cleanup", false)))
                .isInstanceOf(MlLifecycleConflictException.class)
                .hasMessageContaining("Feature snapshot is referenced");
    }

    @Test
    void listsActiveFeatureSnapshotsForDataset() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        MlFeatureSnapshot newest = featureSnapshot(accountId, datasetVersionId, UUID.randomUUID(), "FAILURE_PREDICTION", 30, 20);
        MlFeatureSnapshot older = featureSnapshot(accountId, datasetVersionId, UUID.randomUUID(), "ANOMALY_DETECTION", null, 12);
        when(snapshotRepository.findByAccountIdAndDatasetVersionIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, datasetVersionId, "ACTIVE"))
                .thenReturn(List.of(newest, older));

        List<MlFeatureSnapshotResponse> response = service.list(accountId, datasetVersionId);

        assertThat(response).extracting(MlFeatureSnapshotResponse::id).containsExactly(newest.getId(), older.getId());
        assertThat(response.getFirst().modelFamily()).isEqualTo("FAILURE_PREDICTION");
        assertThat(response.getFirst().horizonDays()).isEqualTo(30);
        assertThat(response.getFirst().lifecycleStatus()).isEqualTo("ACTIVE");
        verify(snapshotRepository).findByAccountIdAndDatasetVersionIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, datasetVersionId, "ACTIVE");
    }

    @Test
    void listsActiveFeatureSnapshotsForAccountNewestFirst() {
        UUID accountId = UUID.randomUUID();
        UUID firstDatasetVersionId = UUID.randomUUID();
        UUID secondDatasetVersionId = UUID.randomUUID();
        MlFeatureSnapshot newest = featureSnapshot(accountId, secondDatasetVersionId, UUID.randomUUID(), "FAILURE_PREDICTION", 30, 20);
        MlFeatureSnapshot older = featureSnapshot(accountId, firstDatasetVersionId, UUID.randomUUID(), "ANOMALY_DETECTION", null, 12);
        when(snapshotRepository.findByAccountIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, "ACTIVE"))
                .thenReturn(List.of(newest, older));

        List<MlFeatureSnapshotResponse> response = service.list(accountId, null);

        assertThat(response).extracting(MlFeatureSnapshotResponse::id).containsExactly(newest.getId(), older.getId());
        assertThat(response).extracting(MlFeatureSnapshotResponse::datasetVersionId)
                .containsExactly(secondDatasetVersionId, firstDatasetVersionId);
        verify(snapshotRepository).findByAccountIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, "ACTIVE");
    }

    @Test
    void featureDataHashChangesWhenFeatureValuesChangeButSchemaDoesNot() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId)));
        when(importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetId)).thenReturn(List.of(importedFile(fileId, datasetId)));
        when(rowRepository.findByImportedFileIdOrderBySourceRowNumber(fileId))
                .thenReturn(rows(accountId, fileId))
                .thenReturn(List.of(
                        row(accountId, fileId, 1, "washer-1", "activePowerW", "2026-07-01T00:00:00Z", 100.0),
                        row(accountId, fileId, 2, "washer-1", "activePowerW", "2026-07-01T01:00:00Z", 140.0),
                        row(accountId, fileId, 3, "washer-1", "activePowerW", "2026-07-01T02:00:00Z", 110.0)));
        MlFeatureSnapshotRequest request = new MlFeatureSnapshotRequest(
                versionId,
                "feature-set-v1",
                "pipeline-v1",
                2,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "label"),
                "ANOMALY_DETECTION",
                null,
                "generic-appliance",
                "template-v1");

        MlFeatureSnapshotResponse first = service.generate(accountId, request);
        MlFeatureSnapshotResponse second = service.generate(accountId, request);

        assertThat(first.featureSchemaHash()).isEqualTo(second.featureSchemaHash());
        assertThat(first.featureDataHash()).isNotEqualTo(second.featureDataHash());
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

        service.generate(accountId, new MlFeatureSnapshotRequest(versionId, null, null, 2, null, null, null, null, null, null));

        ArgumentCaptor<MlFeatureSnapshot> snapshot = ArgumentCaptor.forClass(MlFeatureSnapshot.class);
        verify(snapshotRepository).save(snapshot.capture());
        assertThat(snapshot.getValue().getFeatureConfigJson()).contains("rollingWindowSize");
        assertThat(snapshot.getValue().getFeatureSchemaHash()).startsWith("sha256:");
        assertThat(snapshot.getValue().getFeatureConfigHash()).startsWith("sha256:");
        assertThat(snapshot.getValue().getFeatureDataHash()).startsWith("sha256:");
        assertThat(snapshot.getValue().getRowCount()).isEqualTo(3);
        assertThat(snapshot.getValue().getArtifactUri()).startsWith("db://ml_feature_snapshots/");
        assertThat(snapshot.getValue().getImmutableRowReference()).contains("ml_feature_rows");
        assertThat(snapshot.getValue().getFeatureTemplateId()).isEqualTo("generic-appliance");

        ArgumentCaptor<Iterable<MlFeatureRow>> featureRows = ArgumentCaptor.forClass(Iterable.class);
        verify(featureRowRepository).saveAll(featureRows.capture());
        MlFeatureRow firstRow = featureRows.getValue().iterator().next();
        assertThat(firstRow.getFeatureSnapshotId()).isEqualTo(snapshot.getValue().getId());
        assertThat(firstRow.getFeatureValuesJson()).contains("rollingMean");
        assertThat(firstRow.getSplitAssignment()).isEqualTo("TRAIN");
    }

    @Test
    void schemaIncludesCanonicalFeatureVectorContractForModelFamily() {
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                "FAILURE_PREDICTION",
                30,
                "generic-appliance",
                "template-v1",
                List.of("rolling_stats", "baseline_deviation", "trend", "drift", "maintenance", "device_context", "label"));
        assertThat(contract.contractVersion()).isEqualTo("feature-vector-contract-v1");
        assertThat(contract.requiredColumns()).contains(
                "deviceKey",
                "metricName",
                "timestamp",
                "rollingMean",
                "rollingStddev",
                "baselineDeviationRatio",
                "trendSlope",
                "ewmaValue",
                "cusumPositive",
                "cusumNegative",
                "daysSinceMaintenance",
                "daysUntilPlannedMaintenance",
                "deviceType");
        assertThat(contract.labelColumns()).containsExactly("failureWithinHorizon", "failureOutcomeType");
        assertThat(contract.generatedColumns()).contains("applianceLoadRatio", "applianceActivityFlag");
        assertThat(contract.requiredColumns()).contains("applianceLoadRatio");

        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId)));
        when(importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetId)).thenReturn(List.of(importedFile(fileId, datasetId)));
        when(rowRepository.findByImportedFileIdOrderBySourceRowNumber(fileId)).thenReturn(rows(accountId, fileId));

        service.generate(accountId, new MlFeatureSnapshotRequest(
                versionId,
                null,
                null,
                2,
                null,
                List.of("rolling_stats", "baseline_deviation", "trend", "drift", "maintenance", "device_context", "label"),
                "FAILURE_PREDICTION",
                30,
                "generic-appliance",
                "template-v1"));

        ArgumentCaptor<MlFeatureSnapshot> snapshot = ArgumentCaptor.forClass(MlFeatureSnapshot.class);
        verify(snapshotRepository).save(snapshot.capture());
        assertThat(snapshot.getValue().getFeatureConfigJson()).contains("\"modelFamily\":\"FAILURE_PREDICTION\"");
        assertThat(snapshot.getValue().getFeatureConfigJson()).contains("\"horizonDays\":30");
        assertThat(snapshot.getValue().getFeatureConfigJson()).contains("\"featureTemplateId\":\"generic-appliance\"");
        assertThat(snapshot.getValue().getModelFamily()).isEqualTo("FAILURE_PREDICTION");
        assertThat(snapshot.getValue().getHorizonDays()).isEqualTo(30);
    }

    @Test
    void rejectsFailurePredictionContractWithoutHorizon() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId)).thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId)));
        when(importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetId)).thenReturn(List.of(importedFile(fileId, datasetId)));
        when(rowRepository.findByImportedFileIdOrderBySourceRowNumber(fileId)).thenReturn(rows(accountId, fileId));

        MlFeatureSnapshotRequest request = new MlFeatureSnapshotRequest(
                versionId,
                null,
                null,
                2,
                null,
                List.of("rolling_stats", "baseline_deviation"),
                "FAILURE_PREDICTION",
                null,
                "generic-appliance",
                "template-v1");

        assertThatThrownBy(() -> service.generate(accountId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FAILURE_PREDICTION features require horizonDays");
    }

    @Test
    void genericApplianceTemplateAddsVersionedGeneratedFeatureNames() {
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                "ANOMALY_DETECTION",
                null,
                "generic-appliance",
                "template-v1",
                List.of("rolling_stats", "baseline_deviation"));

        assertThat(contract.featureTemplateId()).isEqualTo("generic-appliance");
        assertThat(contract.featureTemplateVersion()).isEqualTo("template-v1");
        assertThat(contract.generatedColumns()).contains("applianceLoadRatio", "applianceActivityFlag");
        assertThat(contract.requiredColumns()).contains("applianceLoadRatio");
        assertThat(FeatureTemplateCatalog.resolve("generic-appliance", "template-v1").compatibleDeviceTypes())
                .containsExactly("*");
    }

    @Test
    void fridgeFreezerTemplateAddsCompatibleDeviceSpecificFeatureNames() {
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                "ANOMALY_DETECTION",
                null,
                "fridge-freezer",
                "template-v1",
                List.of("rolling_stats", "baseline_deviation", "device_context"));

        assertThat(contract.generatedColumns())
                .contains("fridgeTemperatureBandC", "compressorDutyProxy", "defrostCycleRiskFlag");
        assertThat(contract.requiredColumns()).contains("compressorDutyProxy");
        assertThat(FeatureTemplateCatalog.resolve("fridge-freezer", "template-v1").compatibleDeviceTypes())
                .contains("fridge", "freezer", "refrigerator");
    }

    @Test
    void rejectsUnsupportedFeatureTemplateVersion() {
        assertThatThrownBy(() -> FeatureVectorContract.forConfig(
                "ANOMALY_DETECTION",
                null,
                "generic-appliance",
                "template-v2",
                List.of("rolling_stats")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported ML feature template");
    }

    @Test
    void generatesFeatureRowsForInternalTelemetryDatasetVersions() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId))
                .thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId, MlDatasetSourceType.INTERNAL_TELEMETRY)));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(versionId))
                .thenReturn(List.of(
                        canonicalRow(accountId, versionId, deviceId, 1, "activePowerW", "active_power", "2026-07-01T00:00:00Z", 100.0),
                        canonicalRow(accountId, versionId, deviceId, 2, "activePowerW", "active_power", "2026-07-01T01:00:00Z", 120.0),
                        canonicalRow(accountId, versionId, deviceId, 3, "activePowerW", "active_power", "2026-07-01T02:00:00Z", 110.0)));
        when(labelRepository.findByAccountIdAndDatasetVersionId(accountId, versionId))
                .thenReturn(List.of(horizonLabel(
                        accountId,
                        versionId,
                        deviceId,
                        "activePowerW",
                        "2026-07-01T01:00:00Z",
                        14,
                        MlLabelType.CONFIRMED_FAILURE)));

        MlFeatureSnapshotResponse response = service.generate(accountId, new MlFeatureSnapshotRequest(
                versionId,
                null,
                null,
                2,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context"),
                "FAILURE_PREDICTION",
                14,
                "generic-appliance",
                "template-v1"));

        assertThat(response.rowCount()).isEqualTo(3);
        ArgumentCaptor<Iterable<MlFeatureRow>> featureRows = ArgumentCaptor.forClass(Iterable.class);
        verify(featureRowRepository).saveAll(featureRows.capture());
        MlFeatureRow firstRow = featureRows.getValue().iterator().next();
        assertThat(firstRow.getDeviceId()).isEqualTo(deviceId);
        assertThat(firstRow.getFeatureValuesJson()).contains("rollingMean");
        assertThat(firstRow.getFeatureValuesJson()).contains("deviceType");
        assertThat(firstRow.getFeatureValuesJson()).contains("applianceLoadRatio");
        assertThat(firstRow.getSourceLineageJson()).contains("ml_canonical_metric_rows");
        assertThat(featureRows.getValue())
                .extracting(MlFeatureRow::getLabelValuesJson)
                .anySatisfy(json -> assertThat(json)
                        .contains("\"failureWithinHorizon\":true")
                        .contains("\"failureOutcomeType\":\"CONFIRMED_FAILURE\"")
                        .contains("\"horizonDays\":14"));
    }

    @Test
    void materializesFridgeFreezerTemplateFeatureValues() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(datasetVersionRepository.findById(versionId))
                .thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId, MlDatasetSourceType.INTERNAL_TELEMETRY)));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(versionId))
                .thenReturn(List.of(
                        canonicalRow(accountId, versionId, deviceId, 1, "temperatureC", "temperature_c", "2026-07-01T00:00:00Z", 4.0, "fridge"),
                        canonicalRow(accountId, versionId, deviceId, 2, "temperatureC", "temperature_c", "2026-07-01T01:00:00Z", 8.0, "fridge")));

        MlFeatureSnapshotResponse response = service.generate(accountId, new MlFeatureSnapshotRequest(
                versionId,
                null,
                null,
                2,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "device_context"),
                "ANOMALY_DETECTION",
                null,
                "fridge-freezer",
                "template-v1"));

        assertThat(response.featureTemplateId()).isEqualTo("fridge-freezer");
        assertThat(response.featureTemplateVersion()).isEqualTo("template-v1");
        ArgumentCaptor<Iterable<MlFeatureRow>> featureRows = ArgumentCaptor.forClass(Iterable.class);
        verify(featureRowRepository).saveAll(featureRows.capture());
        MlFeatureRow firstRow = featureRows.getValue().iterator().next();
        assertThat(firstRow.getFeatureValuesJson())
                .contains("fridgeTemperatureBandC")
                .contains("compressorDutyProxy")
                .contains("defrostCycleRiskFlag");
    }

    @Test
    void onlineAndOfflineFeaturesMatchForFixedSinglePointFixture() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant predictionAt = Instant.parse("2026-07-01T02:00:00Z");
        when(datasetVersionRepository.findById(versionId))
                .thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId, MlDatasetSourceType.INTERNAL_TELEMETRY)));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(versionId))
                .thenReturn(List.of(canonicalRow(
                        accountId,
                        versionId,
                        deviceId,
                        1,
                        "activePowerW",
                        "active_power",
                        predictionAt.toString(),
                        120.0)));

        service.generate(accountId, new MlFeatureSnapshotRequest(
                versionId,
                "feature-set-v1",
                "analytics-feature-pipeline-v1",
                1,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context"),
                "ANOMALY_DETECTION",
                null,
                "generic-appliance",
                "template-v1"));

        ArgumentCaptor<Iterable<MlFeatureRow>> featureRows = ArgumentCaptor.forClass(Iterable.class);
        verify(featureRowRepository).saveAll(featureRows.capture());
        MlFeatureRow offlineRow = featureRows.getValue().iterator().next();
        Map<String, Object> offlineFeatures = new ObjectMapper().readValue(offlineRow.getFeatureValuesJson(), Map.class);

        MlInferenceRequest onlineRequest = new MlInferenceRequest(
                deviceId,
                predictionAt,
                Map.of(
                        "metric", "active_power",
                        "metricUnit", "W",
                        "latestValue", 120.0,
                        "mean", 120.0,
                        "min", 120.0,
                        "max", 120.0,
                        "deviceType", "washer"),
                List.of(),
                Map.of("mean", 120.0),
                Map.of("sampleCount", 1),
                Map.of("modelFamily", "ANOMALY_DETECTION"));

        assertThat(onlineRequest.featureGenerationStatus()).isEqualTo("READY");
        assertThat(onlineRequest.featureVector())
                .containsEntry("deviceKey", offlineFeatures.get("deviceKey"))
                .containsEntry("metricName", offlineFeatures.get("metricName"))
                .containsEntry("timestamp", offlineFeatures.get("timestamp"))
                .containsEntry("rollingMean", offlineFeatures.get("rollingMean"))
                .containsEntry("rollingMin", offlineFeatures.get("rollingMin"))
                .containsEntry("rollingMax", offlineFeatures.get("rollingMax"))
                .containsEntry("rollingStddev", offlineFeatures.get("rollingStddev"))
                .containsEntry("baselineDeviationRatio", offlineFeatures.get("baselineDeviationRatio"))
                .containsEntry("missingGapFlag", offlineFeatures.get("missingGapFlag"))
                .containsEntry("samplingGapSeconds", offlineFeatures.get("samplingGapSeconds"))
                .containsEntry("hourOfDay", offlineFeatures.get("hourOfDay"))
                .containsEntry("dayOfWeek", offlineFeatures.get("dayOfWeek"))
                .containsEntry("deviceType", offlineFeatures.get("deviceType"))
                .containsEntry("metricSchemaId", offlineFeatures.get("metricSchemaId"))
                .containsEntry("applianceLoadRatio", offlineFeatures.get("applianceLoadRatio"))
                .containsEntry("applianceActivityFlag", offlineFeatures.get("applianceActivityFlag"));
    }

    @Test
    void onlineAndOfflineFailureFeaturesMatchForPositiveHorizonFixture() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant predictionAt = Instant.parse("2026-07-01T02:00:00Z");
        when(datasetVersionRepository.findById(versionId))
                .thenReturn(Optional.of(datasetVersion(accountId, datasetId, versionId, MlDatasetSourceType.INTERNAL_TELEMETRY)));
        when(canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(versionId))
                .thenReturn(List.of(canonicalRow(
                        accountId,
                        versionId,
                        deviceId,
                        1,
                        "activePowerW",
                        "active_power",
                        predictionAt.toString(),
                        120.0)));

        service.generate(accountId, new MlFeatureSnapshotRequest(
                versionId,
                "feature-set-v1",
                "analytics-feature-pipeline-v1",
                1,
                7200L,
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context"),
                "FAILURE_PREDICTION",
                14,
                "generic-appliance",
                "template-v1"));

        ArgumentCaptor<Iterable<MlFeatureRow>> featureRows = ArgumentCaptor.forClass(Iterable.class);
        verify(featureRowRepository).saveAll(featureRows.capture());
        MlFeatureRow offlineRow = featureRows.getValue().iterator().next();
        Map<String, Object> offlineFeatures = new ObjectMapper().readValue(offlineRow.getFeatureValuesJson(), Map.class);
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                "FAILURE_PREDICTION",
                14,
                "generic-appliance",
                "template-v1",
                List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context"));
        String requiredContractHash = FeatureVectorContract.contractMetadataHash(
                "feature-set-v1",
                "analytics-feature-pipeline-v1",
                contract.contractVersion(),
                contract.modelFamily().name(),
                contract.horizonDays(),
                contract.featureTemplateId(),
                contract.featureTemplateVersion(),
                contract.requiredColumns());

        MlInferenceRequest onlineRequest = new MlInferenceRequest(
                deviceId,
                predictionAt,
                Map.of(
                        "metric", "active_power",
                        "metricUnit", "W",
                        "latestValue", 120.0,
                        "mean", 120.0,
                        "min", 120.0,
                        "max", 120.0,
                        "horizonDays", 14,
                        "deviceType", "washer"),
                List.of(),
                Map.of("mean", 120.0),
                Map.of("sampleCount", 1),
                Map.of(
                        "modelFamily", "FAILURE_PREDICTION",
                        "requiredFeatureContractVersion", "feature-vector-contract-v1",
                        "requiredFeatureSetVersion", "feature-set-v1",
                        "requiredFeatureGeneratorVersion", "analytics-feature-pipeline-v1",
                        "requiredFeatureTemplateId", "generic-appliance",
                        "requiredFeatureTemplateVersion", "template-v1",
                        "requiredHorizonDays", 14,
                        "requiredFeatureContractMetadataHash", requiredContractHash));

        assertThat(onlineRequest.featureGenerationStatus()).isEqualTo("READY");
        assertThat(onlineRequest.featureVector())
                .containsEntry("deviceKey", offlineFeatures.get("deviceKey"))
                .containsEntry("metricName", offlineFeatures.get("metricName"))
                .containsEntry("timestamp", offlineFeatures.get("timestamp"))
                .containsEntry("rollingMean", offlineFeatures.get("rollingMean"))
                .containsEntry("baselineDeviationRatio", offlineFeatures.get("baselineDeviationRatio"))
                .containsEntry("applianceLoadRatio", offlineFeatures.get("applianceLoadRatio"));
        assertThat(onlineRequest.featureContract())
                .containsEntry("horizonDays", 14)
                .containsEntry("requiredFeatureContractMetadataHash", requiredContractHash)
                .containsEntry("contractMetadataHash", requiredContractHash);
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetId, UUID versionId) {
        return datasetVersion(accountId, datasetId, versionId, MlDatasetSourceType.EXTERNAL_IMPORT);
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetId, UUID versionId, MlDatasetSourceType sourceType) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(versionId);
        version.setDatasetId(datasetId);
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(sourceType);
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
        row.setVirtualDeviceKey("virtual:test:" + deviceKey);
        row.setDeviceType("washer");
        row.setMetricName(metric);
        row.setTimestamp(Instant.parse(timestamp));
        row.setNumericValue(value);
        row.setMetadataJson("{}");
        row.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        return row;
    }

    private MlCanonicalMetricRow canonicalRow(
            UUID accountId,
            UUID versionId,
            UUID deviceId,
            long rowNumber,
            String metric,
            String canonicalMetric,
            String timestamp,
            double value) {
        return canonicalRow(accountId, versionId, deviceId, rowNumber, metric, canonicalMetric, timestamp, value, "washer");
    }

    private MlCanonicalMetricRow canonicalRow(
            UUID accountId,
            UUID versionId,
            UUID deviceId,
            long rowNumber,
            String metric,
            String canonicalMetric,
            String timestamp,
            double value,
            String deviceType) {
        MlCanonicalMetricRow row = new MlCanonicalMetricRow();
        row.setId(UUID.nameUUIDFromBytes((deviceId + ":" + rowNumber).getBytes()));
        row.setAccountId(accountId);
        row.setDatasetVersionId(versionId);
        row.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        row.setDeviceId(deviceId);
        row.setVirtualDeviceKey("internal:" + accountId + ":" + deviceId);
        row.setDeviceType(deviceType);
        row.setSourceId("telemetry-service");
        row.setSensorId(metric);
        row.setTimestamp(Instant.parse(timestamp));
        row.setMetricName(metric);
        row.setCanonicalMetricName(canonicalMetric);
        row.setNumericValue(value);
        row.setRawUnit("W");
        row.setCanonicalUnit("W");
        row.setQualityFlags("[]");
        row.setMetadataJson("{}");
        row.setSourceLineage("{}");
        row.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        return row;
    }

    private MlFeatureSnapshot featureSnapshot(
            UUID accountId,
            UUID datasetVersionId,
            UUID snapshotId,
            String modelFamily,
            Integer horizonDays,
            long rowCount) {
        MlFeatureSnapshot snapshot = new MlFeatureSnapshot();
        snapshot.setId(snapshotId);
        snapshot.setAccountId(accountId);
        snapshot.setDatasetVersionId(datasetVersionId);
        snapshot.setFeatureSetVersion("feature-set-v1");
        snapshot.setCodeConfigVersion("analytics-feature-pipeline-v1");
        snapshot.setFeatureConfigJson("{}");
        snapshot.setFeatureSchemaHash("sha256:schema:" + snapshotId);
        snapshot.setFeatureDataHash("sha256:data:" + snapshotId);
        snapshot.setFeatureConfigHash("sha256:config:" + snapshotId);
        snapshot.setRowCount(rowCount);
        snapshot.setRejectedRowCount(0);
        snapshot.setArtifactUri("db://ml_feature_snapshots/" + snapshotId);
        snapshot.setImmutableRowReference("ml_feature_rows:feature_snapshot_id=" + snapshotId);
        snapshot.setModelFamily(modelFamily);
        snapshot.setHorizonDays(horizonDays);
        snapshot.setFeatureTemplateId("generic-appliance");
        snapshot.setFeatureTemplateVersion("template-v1");
        snapshot.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        snapshot.setLifecycleStatus("ACTIVE");
        return snapshot;
    }

    private MlLabel horizonLabel(
            UUID accountId,
            UUID datasetVersionId,
            UUID deviceId,
            String metricName,
            String timestamp,
            int horizonDays,
            MlLabelType labelType) {
        Instant at = Instant.parse(timestamp);
        MlLabel label = new MlLabel();
        label.setId(UUID.randomUUID());
        label.setAccountId(accountId);
        label.setDatasetVersionId(datasetVersionId);
        label.setDeviceId(deviceId);
        label.setMetricName(metricName);
        label.setLabelStartAt(at);
        label.setLabelEndAt(at.plusMillis(1));
        label.setLabel(labelType);
        label.setSource(MlLabelSource.MAINTENANCE_OUTCOME);
        label.setHorizonDays(horizonDays);
        label.setGenerationVersion("failure-outcome-v1");
        label.setLineageHash("sha256:labels");
        label.setSourceOutcomeIdsJson("[]");
        label.setCreatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        label.setUpdatedAt(Instant.parse("2026-07-30T00:00:00Z"));
        return label;
    }
}
