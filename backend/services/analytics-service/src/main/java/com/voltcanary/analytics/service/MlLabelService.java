package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelAudit;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlLabelAuditDto;
import com.voltcanary.analytics.dto.MlLabelDto;
import com.voltcanary.analytics.dto.MlLabelRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlLabelAuditRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlLabelService {

    private final MlLabelRepository labelRepository;
    private final MlLabelAuditRepository auditRepository;

    public MlLabelService(MlLabelRepository labelRepository, MlLabelAuditRepository auditRepository) {
        this.labelRepository = labelRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<MlLabelDto> list(UUID accountId, UUID datasetVersionId) {
        if (datasetVersionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetVersionId is required");
        }
        return labelRepository.findByAccountIdAndDatasetVersionIdAndDeletedAtIsNull(accountId, datasetVersionId).stream()
                .map(this::dto)
                .toList();
    }

    @Transactional
    public MlLabelDto create(UUID accountId, UUID actorId, MlLabelRequest request) {
        validate(request);
        Instant now = Instant.now();
        MlLabel label = new MlLabel();
        label.setId(UUID.randomUUID());
        label.setAccountId(accountId);
        label.setDatasetVersionId(request.datasetVersionId());
        label.setDeviceId(request.deviceId());
        label.setExternalDeviceKey(blankToNull(request.externalDeviceKey()));
        label.setMetricName(blankToNull(request.metricName()));
        label.setLabelStartAt(request.labelStartAt());
        label.setLabelEndAt(request.labelEndAt());
        label.setLabel(request.label());
        label.setSource(MlLabelSource.MANUAL);
        label.setReason(blankToNull(request.reason()));
        label.setCreatedBy(actorId);
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        labelRepository.save(label);
        audit(label.getId(), null, label.getLabel(), actorId, label.getReason(), now);
        return dto(label);
    }

    @Transactional
    public MlLabelDto correct(UUID accountId, UUID actorId, UUID labelId, MlLabelRequest request) {
        MlLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found"));
        if (!accountId.equals(label.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found");
        }
        if (request.label() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label is required");
        }
        MlLabelType previous = label.getLabel();
        label.setLabel(request.label());
        label.setReason(blankToNull(request.reason()));
        label.setUpdatedAt(Instant.now());
        labelRepository.save(label);
        audit(label.getId(), previous, label.getLabel(), actorId, label.getReason(), label.getUpdatedAt());
        return dto(label);
    }

    @Transactional(readOnly = true)
    public List<MlLabelAuditDto> audit(UUID accountId, UUID labelId) {
        MlLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found"));
        if (!accountId.equals(label.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found");
        }
        return auditRepository.findByLabelIdOrderByEditedAtDesc(labelId).stream()
                .map(item -> new MlLabelAuditDto(
                        item.getId(),
                        item.getLabelId(),
                        item.getPreviousLabel(),
                        item.getNewLabel(),
                        item.getEditedBy(),
                        item.getEditedAt(),
                        item.getReason()))
                .toList();
    }

    @Transactional
    public MlLabelDto delete(UUID accountId, UUID actorId, UUID labelId, MlLifecycleActionRequest request) {
        MlLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found"));
        if (!accountId.equals(label.getAccountId()) || label.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found");
        }
        String reason = requiredReason(request);
        if (label.getSource() != MlLabelSource.MANUAL) {
            throw new MlLifecycleConflictException(
                    "GENERATED_LABEL_LINEAGE",
                    "Generated labels must be regenerated or superseded instead of hard-deleted",
                    dependency("label", labelId, label.getSource().name(), label.getLineageHash()));
        }
        Instant now = Instant.now();
        label.setDeletedAt(now);
        label.setDeletedBy(actorId);
        label.setDeletionReason(reason);
        label.setUpdatedAt(now);
        labelRepository.save(label);
        audit(label.getId(), label.getLabel(), label.getLabel(), actorId, "DELETE: " + reason, now);
        return dto(label);
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse impact(UUID accountId, UUID labelId, String action) {
        MlLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found"));
        if (!accountId.equals(label.getAccountId()) || label.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML label not found");
        }
        boolean blocked = label.getSource() != MlLabelSource.MANUAL;
        String message = blocked
                ? "Generated labels must be regenerated or superseded instead of hard-deleted"
                : "Manual label deletion is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(
                normalizedAction(action),
                blocked,
                message,
                List.of(dependency("label", labelId, label.getSource().name(), label.getLineageHash())));
    }

    private void validate(MlLabelRequest request) {
        if (request.datasetVersionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetVersionId is required");
        }
        if (request.deviceId() == null && blankToNull(request.externalDeviceKey()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId or externalDeviceKey is required");
        }
        if (request.labelStartAt() == null || request.labelEndAt() == null || !request.labelEndAt().isAfter(request.labelStartAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label time window must end after it starts");
        }
    }

    private void audit(UUID labelId, MlLabelType previous, MlLabelType current, UUID actorId, String reason, Instant now) {
        MlLabelAudit audit = new MlLabelAudit();
        audit.setId(UUID.randomUUID());
        audit.setLabelId(labelId);
        audit.setPreviousLabel(previous);
        audit.setNewLabel(current);
        audit.setEditedBy(actorId);
        audit.setEditedAt(now);
        audit.setReason(reason);
        auditRepository.save(audit);
    }

    private String requiredReason(MlLifecycleActionRequest request) {
        String value = request == null ? null : request.reason();
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lifecycle reason is required");
        }
        return value.trim();
    }

    private java.util.Map<String, Object> dependency(String type, UUID id, String source, String lineageHash) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("source", source);
        details.put("lineageHash", lineageHash == null ? "" : lineageHash);
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id.toString());
        value.put("details", details);
        return value;
    }

    private String normalizedAction(String action) {
        if (action == null || action.isBlank()) {
            return "delete";
        }
        return action.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private MlLabelDto dto(MlLabel label) {
        return new MlLabelDto(
                label.getId(),
                label.getAccountId(),
                label.getDatasetVersionId(),
                label.getDeviceId(),
                label.getExternalDeviceKey(),
                label.getMetricName(),
                label.getLabelStartAt(),
                label.getLabelEndAt(),
                label.getLabel(),
                label.getSource(),
                label.getCreatedBy(),
                label.getCreatedAt(),
                label.getReason(),
                label.getHorizonDays(),
                label.getGenerationVersion(),
                label.getSourceOutcomeIdsJson(),
                label.getLineageHash());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
