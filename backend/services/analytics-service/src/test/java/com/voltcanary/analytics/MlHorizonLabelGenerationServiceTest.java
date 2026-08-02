package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

class MlHorizonLabelGenerationServiceTest {

    private final MlDatasetVersionRepository datasetVersionRepository = mock(MlDatasetVersionRepository.class);
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository = mock(MlCanonicalMetricRowRepository.class);
    private final MaintenanceOutcomeReadModelRepository outcomeRepository = mock(MaintenanceOutcomeReadModelRepository.class);
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService = mock(MaintenanceWindowProjectionService.class);
    private final MlLabelRepository labelRepository = mock(MlLabelRepository.class);
    private final MlHorizonLabelGenerationService service = new MlHorizonLabelGenerationService(
            datasetVersionRepository,
            canonicalMetricRowRepository,
            outcomeRepository,
            maintenanceWindowProjectionService,
            labelRepository);

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

        var request = new MlHorizonLabelGenerationRequest(datasetVersionId, 7, "test-v1", false);
        var first = service.generate(accountId, actorId, request);
        var second = service.generate(accountId, actorId, request);

        assertThat(first.generatedLabels()).isEqualTo(4);
        assertThat(first.positiveLabels()).isEqualTo(1);
        assertThat(first.normalLabels()).isEqualTo(1);
        assertThat(first.maintenanceExcludedLabels()).isEqualTo(1);
        assertThat(first.unresolvedLabels()).isEqualTo(1);
        assertThat(first.unobservableRows()).isEqualTo(3);
        assertThat(second.lineageHash()).isEqualTo(first.lineageHash());

        ArgumentCaptor<Iterable<MlLabel>> labelsCaptor = ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(labelRepository, org.mockito.Mockito.times(2)).saveAll(labelsCaptor.capture());
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
            assertThat(label.getLineageHash()).isEqualTo(first.lineageHash());
        });
        assertThat(labels.stream()
                .filter(label -> label.getLabel() == MlLabelType.CONFIRMED_FAILURE)
                .findFirst()
                .orElseThrow()
                .getSourceOutcomeIdsJson()).contains(failureOutcomeId.toString());
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
