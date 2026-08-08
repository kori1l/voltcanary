package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import com.voltcanary.analytics.service.MlDatasetService;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlDatasetServiceTest {

    private final MlDatasetRepository datasetRepository = mock(MlDatasetRepository.class);
    private final MlDatasetVersionRepository versionRepository = mock(MlDatasetVersionRepository.class);
    private final MlValidationReportRepository reportRepository = mock(MlValidationReportRepository.class);
    private final MlDatasetLineageRepository lineageRepository = mock(MlDatasetLineageRepository.class);
    private final MlLabelRepository labelRepository = mock(MlLabelRepository.class);
    private final MlFeatureSnapshotRepository featureSnapshotRepository = mock(MlFeatureSnapshotRepository.class);
    private final MlTrainingRunRepository trainingRunRepository = mock(MlTrainingRunRepository.class);
    private final MlModelVersionRepository modelVersionRepository = mock(MlModelVersionRepository.class);
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository = mock(MlCanonicalMetricRowRepository.class);
    private final MaintenanceWindowReadModelRepository maintenanceRepository = mock(MaintenanceWindowReadModelRepository.class);
    private final TelemetryClient telemetryClient = mock(TelemetryClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MlDatasetService service = new MlDatasetService(
            datasetRepository,
            versionRepository,
            reportRepository,
            lineageRepository,
            labelRepository,
            featureSnapshotRepository,
            trainingRunRepository,
            modelVersionRepository,
            canonicalMetricRowRepository,
            maintenanceRepository,
            telemetryClient,
            objectMapper);

    @Test
    void createsReadyDatasetVersionWhenTelemetryCoverageIsSufficient() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to))
                .thenReturn(unsortedReadings(deviceId, "activePowerW", from));

        MlInternalDatasetResponse response = service.createFromInternalTelemetry(accountId, userId,
                new MlInternalDatasetRequest(
                        "July training",
                        null,
                        List.of(deviceId),
                        "washer",
                        null,
                        List.of("activePowerW"),
                        from,
                        to,
                        MlTrainingSplitStrategy.TIME_BASED,
                        null,
                        null,
                        4,
                        7200L));

        assertThat(response.status()).isEqualTo(MlDatasetStatus.READY);
        assertThat(response.acceptedRows()).isEqualTo(4);
        assertThat(response.rejectedRows()).isZero();

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(versionRepository, times(2)).save(version.capture());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.READY);
        assertThat(version.getValue().getReadyAt()).isNotNull();
        assertThat(version.getValue().getImmutableRowReference()).contains("ml_canonical_metric_rows");
        assertThat(version.getValue().getDataHash()).startsWith("sha256:");

        ArgumentCaptor<Iterable<MlCanonicalMetricRow>> canonicalRows = ArgumentCaptor.forClass(Iterable.class);
        verify(canonicalMetricRowRepository).saveAll(canonicalRows.capture());
        List<MlCanonicalMetricRow> materializedRows = toList(canonicalRows.getValue());
        assertThat(materializedRows).hasSize(4);
        assertThat(materializedRows.get(0).getDatasetVersionId()).isEqualTo(version.getValue().getId());
        assertThat(materializedRows.get(0).getSourceType()).isEqualTo(MlDatasetSourceType.INTERNAL_TELEMETRY);
        assertThat(materializedRows.get(0).getCanonicalMetricName()).isEqualTo("active_power");
        assertThat(materializedRows.get(0).getCanonicalUnit()).isEqualTo("W");
        assertThat(materializedRows).extracting(MlCanonicalMetricRow::getTimestamp)
                .containsExactly(
                        from,
                        from.plusSeconds(3600L),
                        from.plusSeconds(2 * 3600L),
                        from.plusSeconds(3 * 3600L));

        ArgumentCaptor<MlDatasetLineage> lineage = ArgumentCaptor.forClass(MlDatasetLineage.class);
        verify(lineageRepository).save(lineage.capture());
        JsonNode filter = readTree(lineage.getValue().getFilterJson());
        assertThat(filter.path("materializedRowCount").asInt()).isEqualTo(4);
        assertThat(filter.path("materializedRowPreview").get(0).path("timestamp").asText())
                .isEqualTo(from.toString());
        assertThat(filter.path("materializedRowPreview").get(0).path("metricName").asText())
                .isEqualTo("activePowerW");
        assertThat(filter.path("materializedRowPreview").get(0).path("canonicalMetricName").asText())
                .isEqualTo("active_power");
        assertThat(filter.path("materializedRowPreview").get(0).path("canonicalUnit").asText())
                .isEqualTo("W");
        assertThat(filter.path("materializedRowPreview").get(3).path("timestamp").asText())
                .isEqualTo(from.plusSeconds(3 * 3600L).toString());
    }

    @Test
    void storesFailedDatasetVersionWhenMaterializationPersistenceFails() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to))
                .thenReturn(unsortedReadings(deviceId, "activePowerW", from));
        when(canonicalMetricRowRepository.saveAll(any()))
                .thenThrow(new RuntimeException("database password leaked in stack trace"));

        MlInternalDatasetResponse response = service.createFromInternalTelemetry(accountId, userId,
                new MlInternalDatasetRequest(
                        "July training",
                        null,
                        List.of(deviceId),
                        "washer",
                        null,
                        List.of("activePowerW"),
                        from,
                        to,
                        MlTrainingSplitStrategy.TIME_BASED,
                        null,
                        null,
                        4,
                        7200L));

        assertThat(response.status()).isEqualTo(MlDatasetStatus.FAILED);
        assertThat(response.readyAt()).isNull();

        ArgumentCaptor<MlDatasetVersion> version = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(versionRepository, times(2)).save(version.capture());
        assertThat(version.getAllValues().get(0).getStatus()).isEqualTo(MlDatasetStatus.FAILED);
        assertThat(version.getValue().getFailureReason()).isEqualTo("Internal telemetry materialization failed.");
        assertThat(version.getValue().getFailureReason()).doesNotContain("password");
        assertThat(version.getValue().getImmutableRowReference()).isNull();
        assertThat(version.getValue().getDataHash()).isNull();

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(MlValidationReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getSummary()).isEqualTo("Telemetry selection failed validation.");
    }

    @Test
    void storesFailedDatasetVersionAndValidationReasonsWhenCoverageIsInsufficient() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to))
                .thenReturn(readings(deviceId, "activePowerW", from, 1));

        MlInternalDatasetResponse response = service.createFromInternalTelemetry(accountId, userId,
                new MlInternalDatasetRequest(
                        "Thin data",
                        null,
                        List.of(deviceId),
                        null,
                        null,
                        List.of("activePowerW"),
                        from,
                        to,
                        null,
                        null,
                        null,
                        4,
                        null));

        assertThat(response.status()).isEqualTo(MlDatasetStatus.FAILED);
        assertThat(response.validationMessages()).anySatisfy(message -> assertThat(message).contains("minimum is 4"));

        ArgumentCaptor<MlValidationReport> report = ArgumentCaptor.forClass(MlValidationReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getRejectedRows()).isEqualTo(3);
        assertThat(report.getValue().getRejectedReasonsJson()).contains("INSUFFICIENT_SAMPLES");
    }

    @Test
    void repeatedMaterializationProducesSameDataHashForUnchangedInput() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        MlInternalDatasetRequest request = new MlInternalDatasetRequest(
                "July training",
                null,
                List.of(deviceId),
                "washer",
                null,
                List.of("activePowerW"),
                from,
                to,
                MlTrainingSplitStrategy.TIME_BASED,
                null,
                null,
                4,
                7200L);
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to))
                .thenReturn(unsortedReadings(deviceId, "activePowerW", from))
                .thenReturn(unsortedReadings(deviceId, "activePowerW", from));

        service.createFromInternalTelemetry(accountId, userId, request);
        service.createFromInternalTelemetry(accountId, userId, request);

        ArgumentCaptor<MlDatasetVersion> versions = ArgumentCaptor.forClass(MlDatasetVersion.class);
        verify(versionRepository, times(4)).save(versions.capture());
        assertThat(versions.getAllValues()).hasSize(4);
        MlDatasetVersion firstFinalVersion = versions.getAllValues().get(1);
        MlDatasetVersion secondFinalVersion = versions.getAllValues().get(3);
        assertThat(firstFinalVersion.getDataHash()).startsWith("sha256:");
        assertThat(firstFinalVersion.getDataHash()).isEqualTo(secondFinalVersion.getDataHash());
        assertThat(firstFinalVersion.getId()).isNotEqualTo(secondFinalVersion.getId());
    }

    @Test
    void listsLatestDatasetVersionsWithValidationAndLineageDetail() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-10T00:00:00Z");
        MlDataset dataset = new MlDataset();
        dataset.setId(datasetId);
        dataset.setAccountId(accountId);
        dataset.setName("Washer July");
        dataset.setDescription("Training data");
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedAt(createdAt);
        dataset.setUpdatedAt(createdAt);
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(versionId);
        version.setDatasetId(datasetId);
        version.setAccountId(accountId);
        version.setVersion(2);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(MlDatasetStatus.READY);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("feature-config-v1");
        version.setArtifactUri("s3://voltcanary-ml/datasets/" + versionId + "/rows.jsonl");
        version.setArtifactHash("sha256:artifact");
        version.setImmutableRowReference("ml_canonical_metric_rows:dataset_version_id=" + versionId);
        version.setDataHash("sha256:data");
        version.setCreatedAt(createdAt);
        version.setReadyAt(createdAt);
        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(versionId);
        report.setAcceptedRows(120);
        report.setRejectedRows(0);
        report.setWarningCount(1);
        report.setRejectedReasonsJson("{}");
        report.setMappingStatsJson("{\"accepted:activePowerW\":120}");
        report.setQualityProfileJson("{\"rowCount\":120,\"deviceCount\":1,\"metricCount\":1,\"maxSamplingGapSeconds\":3600}");
        report.setSummary("Ready");
        report.setCreatedAt(createdAt);
        MlDatasetLineage lineage = new MlDatasetLineage();
        lineage.setId(UUID.randomUUID());
        lineage.setDatasetVersionId(versionId);
        lineage.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        lineage.setSourceReference("telemetry-service");
        lineage.setFilterJson("{}");
        lineage.setSelectedDevicesJson("[]");
        lineage.setSelectedMetricsJson("[\"activePowerW\"]");
        lineage.setContentHash("sha256:lineage");
        lineage.setCreatedAt(createdAt);
        when(datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(dataset));
        when(versionRepository.findByDatasetIdAndStatusNotOrderByVersionDesc(datasetId, MlDatasetStatus.DELETED))
                .thenReturn(List.of(version));
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(reportRepository.findByDatasetVersionIdOrderByCreatedAtDesc(versionId)).thenReturn(List.of(report));
        when(lineageRepository.findByDatasetVersionId(versionId)).thenReturn(List.of(lineage));

        assertThat(service.list(accountId))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.name()).isEqualTo("Washer July");
                    assertThat(summary.datasetVersionId()).isEqualTo(versionId);
                    assertThat(summary.status()).isEqualTo(MlDatasetStatus.READY);
                    assertThat(summary.artifactUri()).contains(versionId.toString());
                    assertThat(summary.artifactHash()).isEqualTo("sha256:artifact");
                    assertThat(summary.immutableRowReference()).contains("ml_canonical_metric_rows");
                    assertThat(summary.dataHash()).isEqualTo("sha256:data");
                });
        assertThat(service.detail(accountId, versionId)).satisfies(detail -> {
            assertThat(detail.validationReports()).singleElement().satisfies(item -> {
                assertThat(item.acceptedRows()).isEqualTo(120);
                assertThat(item.mappingStats()).containsEntry("accepted:activePowerW", 120L);
                assertThat(item.qualityProfile()).containsEntry("rowCount", 120);
                assertThat(item.qualityProfile()).containsEntry("deviceCount", 1);
            });
            assertThat(detail.lineage()).singleElement()
                    .satisfies(item -> assertThat(item.contentHash()).isEqualTo("sha256:lineage"));
        });
    }

    @Test
    void defaultDatasetListHidesArchivedVersionsAndFilterShowsThem() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-10T00:00:00Z");
        MlDataset dataset = dataset(datasetId, accountId, createdAt);
        MlDatasetVersion version = datasetVersion(datasetId, accountId, versionId, createdAt, MlDatasetStatus.ARCHIVED);
        when(datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(dataset));
        when(versionRepository.findByDatasetIdAndStatusNotOrderByVersionDesc(datasetId, MlDatasetStatus.DELETED))
                .thenReturn(List.of(version));
        when(versionRepository.findByDatasetIdOrderByVersionDesc(datasetId)).thenReturn(List.of(version));

        assertThat(service.list(accountId)).isEmpty();
        assertThat(service.list(accountId, "archived")).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(MlDatasetStatus.ARCHIVED));
    }

    @Test
    void datasetImpactIncludesReferenceCountsBeforeDeletion() {
        UUID accountId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        MlDatasetVersion version = datasetVersion(datasetId, accountId, versionId, Instant.parse("2026-07-10T00:00:00Z"), MlDatasetStatus.FAILED);
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(labelRepository.countByDatasetVersionIdAndDeletedAtIsNull(versionId)).thenReturn(2L);
        when(trainingRunRepository.countByDatasetVersionId(versionId)).thenReturn(1L);

        var impact = service.impact(accountId, versionId, "delete");

        assertThat(impact.blocked()).isTrue();
        assertThat(impact.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency).containsEntry("type", "datasetVersion");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> details = (java.util.Map<String, Object>) dependency.get("details");
            assertThat(details)
                    .containsEntry("labels", 2L)
                    .containsEntry("trainingRuns", 1L);
        });
    }

    @Test
    void blocksDeletingReferencedDatasetVersion() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(versionId);
        version.setAccountId(accountId);
        version.setDatasetId(datasetId);
        version.setStatus(MlDatasetStatus.FAILED);
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
        when(labelRepository.countByDatasetVersionIdAndDeletedAtIsNull(versionId)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(accountId, actorId, versionId, new MlLifecycleActionRequest("cleanup", false)))
                .isInstanceOf(MlLifecycleConflictException.class)
                .hasMessageContaining("Dataset version is referenced");
    }

    private TelemetryChartResponse readings(UUID deviceId, String metric, Instant start, int count) {
        return new TelemetryChartResponse(deviceId, metric, java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new TelemetryChartResponse.Point(start.plusSeconds(index * 3600L), 10.0 + index))
                .toList());
    }

    private TelemetryChartResponse unsortedReadings(UUID deviceId, String metric, Instant start) {
        return new TelemetryChartResponse(deviceId, metric, List.of(
                new TelemetryChartResponse.Point(start.plusSeconds(2 * 3600L), 12.0),
                new TelemetryChartResponse.Point(start, 10.0),
                new TelemetryChartResponse.Point(start.plusSeconds(3 * 3600L), 13.0),
                new TelemetryChartResponse.Point(start.plusSeconds(3600L), 11.0)));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new AssertionError("Expected valid JSON", exception);
        }
    }

    private List<MlCanonicalMetricRow> toList(Iterable<MlCanonicalMetricRow> rows) {
        java.util.ArrayList<MlCanonicalMetricRow> values = new java.util.ArrayList<>();
        rows.forEach(values::add);
        return values;
    }

    private MlDataset dataset(UUID datasetId, UUID accountId, Instant createdAt) {
        MlDataset dataset = new MlDataset();
        dataset.setId(datasetId);
        dataset.setAccountId(accountId);
        dataset.setName("Washer July");
        dataset.setDescription("Training data");
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedAt(createdAt);
        dataset.setUpdatedAt(createdAt);
        return dataset;
    }

    private MlDatasetVersion datasetVersion(UUID datasetId, UUID accountId, UUID versionId, Instant createdAt, MlDatasetStatus status) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(versionId);
        version.setDatasetId(datasetId);
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(status);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("feature-config-v1");
        version.setCreatedAt(createdAt);
        return version;
    }
}
