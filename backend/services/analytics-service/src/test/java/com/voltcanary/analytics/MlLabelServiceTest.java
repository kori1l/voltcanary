package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelAudit;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlLabelRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.repository.MlLabelAuditRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import com.voltcanary.analytics.service.MlLabelService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlLabelServiceTest {

    private final MlLabelRepository labelRepository = mock(MlLabelRepository.class);
    private final MlLabelAuditRepository auditRepository = mock(MlLabelAuditRepository.class);
    private final MlLabelService service = new MlLabelService(labelRepository, auditRepository);

    @Test
    void createsAndCorrectsManualLabelsWithAuditTrail() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T01:00:00Z");

        var created = service.create(accountId, actorId, new MlLabelRequest(
                datasetVersionId,
                null,
                "external-1",
                "activePowerW",
                start,
                end,
                MlLabelType.ANOMALY,
                "operator review"));

        assertThat(created.source()).isEqualTo(MlLabelSource.MANUAL);
        assertThat(created.label()).isEqualTo(MlLabelType.ANOMALY);
        ArgumentCaptor<MlLabel> labelCaptor = ArgumentCaptor.forClass(MlLabel.class);
        verify(labelRepository).save(labelCaptor.capture());
        MlLabel stored = labelCaptor.getValue();
        assertThat(stored.getExternalDeviceKey()).isEqualTo("external-1");
        verify(auditRepository).save(any(MlLabelAudit.class));

        when(labelRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        var corrected = service.correct(accountId, actorId, stored.getId(), new MlLabelRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                MlLabelType.NORMAL,
                "false positive"));

        assertThat(corrected.label()).isEqualTo(MlLabelType.NORMAL);
        assertThat(stored.getReason()).isEqualTo("false positive");
    }

    @Test
    void listsLabelsAndAuditByDatasetVersion() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        MlLabel label = new MlLabel();
        label.setId(labelId);
        label.setAccountId(accountId);
        label.setDatasetVersionId(datasetVersionId);
        label.setExternalDeviceKey("external-1");
        label.setLabelStartAt(Instant.parse("2026-07-01T00:00:00Z"));
        label.setLabelEndAt(Instant.parse("2026-07-01T01:00:00Z"));
        label.setLabel(MlLabelType.ANOMALY);
        label.setSource(MlLabelSource.MANUAL);
        label.setCreatedAt(Instant.parse("2026-07-01T02:00:00Z"));
        label.setUpdatedAt(Instant.parse("2026-07-01T02:00:00Z"));
        MlLabelAudit audit = new MlLabelAudit();
        audit.setId(UUID.randomUUID());
        audit.setLabelId(labelId);
        audit.setPreviousLabel(MlLabelType.ANOMALY);
        audit.setNewLabel(MlLabelType.NORMAL);
        audit.setEditedAt(Instant.parse("2026-07-01T03:00:00Z"));
        audit.setReason("false positive");
        when(labelRepository.findByAccountIdAndDatasetVersionIdAndDeletedAtIsNull(accountId, datasetVersionId))
                .thenReturn(List.of(label));
        when(labelRepository.findById(labelId)).thenReturn(Optional.of(label));
        when(auditRepository.findByLabelIdOrderByEditedAtDesc(labelId)).thenReturn(List.of(audit));

        assertThat(service.list(accountId, datasetVersionId)).singleElement()
                .satisfies(item -> assertThat(item.externalDeviceKey()).isEqualTo("external-1"));
        assertThat(service.audit(accountId, labelId)).singleElement()
                .satisfies(item -> {
                    assertThat(item.previousLabel()).isEqualTo(MlLabelType.ANOMALY);
                    assertThat(item.newLabel()).isEqualTo(MlLabelType.NORMAL);
                });
    }

    @Test
    void supportsFailureOutcomeLabelTypesInAuditTrail() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T01:00:00Z");

        var created = service.create(accountId, actorId, new MlLabelRequest(
                datasetVersionId,
                UUID.randomUUID(),
                null,
                "activePowerW",
                start,
                end,
                MlLabelType.CONFIRMED_FAILURE,
                "outcome confirmed"));

        assertThat(created.label()).isEqualTo(MlLabelType.CONFIRMED_FAILURE);
        ArgumentCaptor<MlLabelAudit> auditCaptor = ArgumentCaptor.forClass(MlLabelAudit.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getNewLabel()).isEqualTo(MlLabelType.CONFIRMED_FAILURE);
    }

    @Test
    void blocksDeletingGeneratedLabelsWithoutRegeneration() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        MlLabel label = new MlLabel();
        label.setId(labelId);
        label.setAccountId(accountId);
        label.setSource(MlLabelSource.MAINTENANCE_OUTCOME);
        label.setLabel(MlLabelType.CONFIRMED_FAILURE);
        label.setLineageHash("sha256:labels");
        when(labelRepository.findById(labelId)).thenReturn(Optional.of(label));

        assertThatThrownBy(() -> service.delete(accountId, actorId, labelId, new MlLifecycleActionRequest("cleanup", false)))
                .isInstanceOf(MlLifecycleConflictException.class)
                .hasMessageContaining("Generated labels");
    }
}
