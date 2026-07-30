package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import com.voltcanary.analytics.service.MlDatasetService;
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
    private final MaintenanceWindowReadModelRepository maintenanceRepository = mock(MaintenanceWindowReadModelRepository.class);
    private final TelemetryClient telemetryClient = mock(TelemetryClient.class);
    private final MlDatasetService service = new MlDatasetService(
            datasetRepository,
            versionRepository,
            reportRepository,
            lineageRepository,
            maintenanceRepository,
            telemetryClient,
            new ObjectMapper());

    @Test
    void createsReadyDatasetVersionWhenTelemetryCoverageIsSufficient() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to))
                .thenReturn(readings(deviceId, "activePowerW", from, 4));

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
        verify(versionRepository).save(version.capture());
        assertThat(version.getValue().getStatus()).isEqualTo(MlDatasetStatus.READY);
        assertThat(version.getValue().getReadyAt()).isNotNull();
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
        when(versionRepository.findByDatasetIdOrderByVersionDesc(datasetId)).thenReturn(List.of(version));
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
                });
        assertThat(service.detail(accountId, versionId)).satisfies(detail -> {
            assertThat(detail.validationReports()).singleElement().satisfies(item -> {
                assertThat(item.acceptedRows()).isEqualTo(120);
                assertThat(item.mappingStats()).containsEntry("accepted:activePowerW", 120L);
            });
            assertThat(detail.lineage()).singleElement()
                    .satisfies(item -> assertThat(item.contentHash()).isEqualTo("sha256:lineage"));
        });
    }

    private TelemetryChartResponse readings(UUID deviceId, String metric, Instant start, int count) {
        return new TelemetryChartResponse(deviceId, metric, java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new TelemetryChartResponse.Point(start.plusSeconds(index * 3600L), 10.0 + index))
                .toList());
    }
}
