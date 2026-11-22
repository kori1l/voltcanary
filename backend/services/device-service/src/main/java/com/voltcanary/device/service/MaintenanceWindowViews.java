package com.voltcanary.device.service;

import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import java.time.Instant;

final class MaintenanceWindowViews {

    private MaintenanceWindowViews() {}

    static MaintenanceWindowResponse toResponse(MaintenanceWindow window) {
        return new MaintenanceWindowResponse(
            window.getId(),
            window.getAccountId(),
            window.getDeviceId(),
            window.getOperationId(),
            window.getTitle(),
            window.getDescription(),
            window.getStartAt(),
            window.getEndAt(),
            MaintenanceValues.status(window, Instant.now()),
            window.isSuppressAlerts(),
            window.isExcludeFromAnalytics(),
            window.isExcludeFromMl(),
            window.getCancelledAt(),
            window.getCancellationReason(),
            window.getCreatedBy(),
            window.getUpdatedBy(),
            window.getCreatedAt(),
            window.getUpdatedAt(),
            window.getVersion()
        );
    }
}
