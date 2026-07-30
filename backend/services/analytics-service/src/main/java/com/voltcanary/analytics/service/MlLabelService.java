package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelAudit;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlLabelAuditDto;
import com.voltcanary.analytics.dto.MlLabelDto;
import com.voltcanary.analytics.dto.MlLabelRequest;
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
        return labelRepository.findByAccountIdAndDatasetVersionId(accountId, datasetVersionId).stream()
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
                label.getReason());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
