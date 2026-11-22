package com.voltcanary.device.service;

import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.repository.MaintenanceOperationRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class MaintenanceWindowOperationSync {
    private final MaintenanceWindowRepository repository;
    private final MaintenanceOperationRepository operationRepository;

    MaintenanceWindowOperationSync(
            MaintenanceWindowRepository windows, MaintenanceOperationRepository operations) {
        repository = windows;
        operationRepository = operations;
    }

    void synchronize(MaintenanceWindow window, UUID userId, String cancellationReason) {
        if (window.getOperationId() == null) {
            return;
        }
        List<MaintenanceWindow> operationWindows =
                repository.findByOperationIdOrderByStartAtAsc(window.getOperationId());
        if (operationWindows.size() != 1) {
            return;
        }
        operationRepository
                .findById(window.getOperationId())
                .ifPresent(
                        operation -> {
                            operation.setTitle(window.getTitle());
                            operation.setDescription(window.getDescription());
                            operation.setStartAt(window.getStartAt());
                            operation.setEndAt(window.getEndAt());
                            operation.setSuppressAlerts(window.isSuppressAlerts());
                            operation.setExcludeFromAnalytics(window.isExcludeFromAnalytics());
                            operation.setExcludeFromMl(window.isExcludeFromMl());
                            if (window.getCancelledAt() != null) {
                                operation.setCancelledAt(window.getCancelledAt());
                                operation.setCancellationReason(cancellationReason);
                            }
                            operation.setUpdatedBy(userId);
                            operation.setUpdatedAt(Instant.now());
                            operation.setVersion(operation.getVersion() + 1);
                            operationRepository.save(operation);
                        });
    }
}
