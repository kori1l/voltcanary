package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.dto.AlertResponse;

public final class AlertViews {
    private AlertViews() { }
    public static AlertResponse response(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getDeviceId(),
                alert.getAccountId(),
                alert.getSourceEventId(),
                alert.getAnomalyId(),
                alert.getRiskScoreId(),
                alert.getRuleId(),
                alert.getCalculationWindowStart(),
                alert.getCalculationWindowEnd(),
                alert.getCalculationInputHash(),
                alert.getAlertType(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                alert.getAcknowledgedAt(),
                alert.getAcknowledgedBy(),
                alert.getResolvedAt(),
                alert.getResolvedBy(),
                alert.getAssignedTo(),
                alert.getResolutionReason());
    }
}
