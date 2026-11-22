package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceOperationRequest;
import com.voltcanary.device.dto.MaintenanceOperationResponse;
import com.voltcanary.device.event.MaintenanceWindowEventPublisher;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.repository.MaintenanceOperationRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaintenanceOperationService {

    private final MaintenanceQueries queries;
    private final MaintenanceOperationViews views;
    private final AccountService accountService;
    private final DeviceService deviceService;
    private final MaintenanceOperationRepository operationRepository;
    private final MaintenanceWindowRepository windowRepository;
    private final DeviceAuditService auditService;
    private final MaintenanceWindowEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MaintenanceOperationResponse> list(
        AuthenticatedUser user,
        Instant from,
        Instant to,
        List<UUID> deviceIds,
        UUID locationId,
        String status,
        boolean includeCancelled,
        Integer limit,
        Integer offset
    ) {
        Account account = accountService.currentAccount(user);
        MaintenanceValues.validateQueryRange(from, to);
        String normalizedStatus = normalizeStatus(status);
        List<UUID> normalizedDeviceIds = MaintenanceValues.normalizeDeviceIds(deviceIds);
        var operations = queries.operations(
            MaintenanceOperationFilters.matching(
                account.getId(),
                from,
                to,
                normalizedStatus,
                includeCancelled,
                normalizedDeviceIds,
                locationId
            ),
            MaintenanceValues.normalizedOffset(offset),
            MaintenanceValues.normalizedLimit(limit)
        );
        var children = views.devices(operations);
        return operations
            .stream()
            .map(operation ->
                views.toResponse(operation, children.getOrDefault(operation.getId(), List.of()))
            )
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MaintenanceOperationResponse get(AuthenticatedUser user, UUID operationId) {
        Account account = accountService.currentAccount(user);
        return views.toResponse(loadOwned(account.getId(), operationId));
    }

    @Transactional
    public MaintenanceOperationResponse create(
        AuthenticatedUser user,
        MaintenanceOperationRequest request
    ) {
        Account account = accountService.currentAccount(user);
        MaintenanceValues.validateRange(request.startAt(), request.endAt());
        List<Device> devices = loadOwnedDevices(user, request.deviceIds());
        Instant now = Instant.now();
        MaintenanceOperation operation = new MaintenanceOperation();
        operation.setId(UUID.randomUUID());
        operation.setAccountId(account.getId());
        applyRequest(operation, request);
        operation.setCreatedBy(user.id());
        operation.setUpdatedBy(user.id());
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        operation.setVersion(1);
        MaintenanceOperation savedOperation = operationRepository.save(operation);

        for (Device device : devices) {
            MaintenanceWindow window = newWindow(savedOperation, device, user.id(), now);
            MaintenanceWindow savedWindow = windowRepository.save(window);
            auditService.record(
                user,
                savedWindow.getAccountId(),
                "MAINTENANCE_WINDOW_CREATED",
                "MAINTENANCE_WINDOW",
                savedWindow.getId()
            );
            eventPublisher.publish("MAINTENANCE_WINDOW_CREATED", savedWindow, user.id());
        }
        auditService.record(
            user,
            savedOperation.getAccountId(),
            "MAINTENANCE_OPERATION_CREATED",
            "MAINTENANCE_OPERATION",
            savedOperation.getId()
        );
        return views.toResponse(savedOperation);
    }

    @Transactional
    public MaintenanceOperationResponse update(
        AuthenticatedUser user,
        UUID operationId,
        MaintenanceOperationRequest request
    ) {
        Account account = accountService.currentAccount(user);
        MaintenanceOperation operation = loadOwned(account.getId(), operationId);
        if (operation.getCancelledAt() != null) {
            throw new IllegalArgumentException(
                "Cancelled maintenance operations cannot be updated"
            );
        }
        if (MaintenanceValues.isFinished(operation, Instant.now())) {
            throw new IllegalArgumentException("Finished maintenance operations cannot be updated");
        }
        MaintenanceValues.validateRange(request.startAt(), request.endAt());
        List<Device> requestedDevices = loadOwnedDevices(user, request.deviceIds());
        Map<UUID, Device> requestedById = new LinkedHashMap<>();
        requestedDevices.forEach(device -> requestedById.put(device.getId(), device));
        List<MaintenanceWindow> windows = windowRepository.findByOperationIdOrderByStartAtAsc(
            operation.getId()
        );
        Map<UUID, MaintenanceWindow> existingByDeviceId = new LinkedHashMap<>();
        windows.forEach(window -> existingByDeviceId.put(window.getDeviceId(), window));

        applyRequest(operation, request);
        touch(operation, user.id());
        MaintenanceOperation savedOperation = operationRepository.save(operation);
        Instant now = Instant.now();

        for (MaintenanceWindow window : windows) {
            if (
                !requestedById.containsKey(window.getDeviceId()) &&
                window.getCancelledAt() == null &&
                !MaintenanceValues.isFinished(window, now)
            ) {
                window.setCancelledAt(now);
                window.setCancellationReason("Removed from maintenance operation.");
                touch(window, user.id());
                windowRepository.save(window);
                auditService.record(
                    user,
                    window.getAccountId(),
                    "MAINTENANCE_WINDOW_CANCELLED",
                    "MAINTENANCE_WINDOW",
                    window.getId()
                );
                eventPublisher.publish("MAINTENANCE_WINDOW_CANCELLED", window, user.id());
            }
        }

        for (Device device : requestedDevices) {
            MaintenanceWindow existing = existingByDeviceId.get(device.getId());
            if (existing == null) {
                MaintenanceWindow created = windowRepository.save(
                    newWindow(savedOperation, device, user.id(), now)
                );
                auditService.record(
                    user,
                    created.getAccountId(),
                    "MAINTENANCE_WINDOW_CREATED",
                    "MAINTENANCE_WINDOW",
                    created.getId()
                );
                eventPublisher.publish("MAINTENANCE_WINDOW_CREATED", created, user.id());
            } else if (
                existing.getCancelledAt() == null && !MaintenanceValues.isFinished(existing, now)
            ) {
                applyOperation(existing, savedOperation);
                touch(existing, user.id());
                windowRepository.save(existing);
                auditService.record(
                    user,
                    existing.getAccountId(),
                    "MAINTENANCE_WINDOW_UPDATED",
                    "MAINTENANCE_WINDOW",
                    existing.getId()
                );
                eventPublisher.publish("MAINTENANCE_WINDOW_UPDATED", existing, user.id());
            }
        }

        auditService.record(
            user,
            savedOperation.getAccountId(),
            "MAINTENANCE_OPERATION_UPDATED",
            "MAINTENANCE_OPERATION",
            savedOperation.getId()
        );
        return views.toResponse(savedOperation);
    }

    @Transactional
    public MaintenanceOperationResponse cancel(
        AuthenticatedUser user,
        UUID operationId,
        CancelMaintenanceWindowRequest request
    ) {
        Account account = accountService.currentAccount(user);
        MaintenanceOperation operation = loadOwned(account.getId(), operationId);
        if (operation.getCancelledAt() == null) {
            Instant now = Instant.now();
            operation.setCancelledAt(now);
            operation.setCancellationReason(
                MaintenanceValues.normalizeOptional(
                    request == null ? null : request.reason(),
                    1000,
                    "reason"
                )
            );
            touch(operation, user.id());
            operationRepository.save(operation);
            for (MaintenanceWindow window : windowRepository.findByOperationIdOrderByStartAtAsc(
                operation.getId()
            )) {
                if (window.getCancelledAt() == null && !MaintenanceValues.isFinished(window, now)) {
                    window.setCancelledAt(now);
                    window.setCancellationReason(operation.getCancellationReason());
                    touch(window, user.id());
                    windowRepository.save(window);
                    auditService.record(
                        user,
                        window.getAccountId(),
                        "MAINTENANCE_WINDOW_CANCELLED",
                        "MAINTENANCE_WINDOW",
                        window.getId()
                    );
                    eventPublisher.publish("MAINTENANCE_WINDOW_CANCELLED", window, user.id());
                }
            }
            auditService.record(
                user,
                operation.getAccountId(),
                "MAINTENANCE_OPERATION_CANCELLED",
                "MAINTENANCE_OPERATION",
                operation.getId()
            );
        }
        return views.toResponse(operation);
    }

    @Transactional
    public MaintenanceOperationResponse finishNow(AuthenticatedUser user, UUID operationId) {
        Account account = accountService.currentAccount(user);
        MaintenanceOperation operation = loadOwned(account.getId(), operationId);
        Instant now = Instant.now();
        if (operation.getCancelledAt() != null) {
            throw new IllegalArgumentException(
                "Cancelled maintenance operations cannot be finished"
            );
        }
        if (now.isBefore(operation.getStartAt())) {
            throw new IllegalArgumentException(
                "Scheduled maintenance operations cannot be finished before they start"
            );
        }
        if (now.isBefore(operation.getEndAt())) {
            operation.setEndAt(now);
            touch(operation, user.id());
            operationRepository.save(operation);
        }
        for (MaintenanceWindow window : windowRepository.findByOperationIdOrderByStartAtAsc(
            operation.getId()
        )) {
            if (
                window.getCancelledAt() == null &&
                !now.isBefore(window.getStartAt()) &&
                now.isBefore(window.getEndAt())
            ) {
                window.setEndAt(now);
                touch(window, user.id());
                windowRepository.save(window);
                auditService.record(
                    user,
                    window.getAccountId(),
                    "MAINTENANCE_WINDOW_FINISHED_NOW",
                    "MAINTENANCE_WINDOW",
                    window.getId()
                );
                eventPublisher.publish("MAINTENANCE_WINDOW_FINISHED", window, user.id());
            }
        }
        auditService.record(
            user,
            operation.getAccountId(),
            "MAINTENANCE_OPERATION_FINISHED_NOW",
            "MAINTENANCE_OPERATION",
            operation.getId()
        );
        return views.toResponse(operation);
    }

    private List<Device> loadOwnedDevices(AuthenticatedUser user, List<UUID> deviceIds) {
        List<UUID> uniqueIds = MaintenanceValues.normalizeDeviceIds(deviceIds);
        if (uniqueIds.isEmpty()) {
            throw new IllegalArgumentException("At least one device is required");
        }
        List<Device> devices = new ArrayList<>();
        for (UUID deviceId : uniqueIds) {
            Device device = deviceService.findOwned(user, deviceId);
            if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
                throw new IllegalArgumentException(
                    "Decommissioned devices cannot receive maintenance windows"
                );
            }
            devices.add(device);
        }
        return devices;
    }

    private MaintenanceOperation loadOwned(UUID accountId, UUID operationId) {
        return operationRepository
            .findByIdAndAccountId(operationId, accountId)
            .orElseThrow(() -> new NotFoundException("Maintenance operation not found"));
    }

    private MaintenanceWindow newWindow(
        MaintenanceOperation operation,
        Device device,
        UUID userId,
        Instant now
    ) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(UUID.randomUUID());
        window.setAccountId(operation.getAccountId());
        window.setDeviceId(device.getId());
        window.setOperationId(operation.getId());
        applyOperation(window, operation);
        window.setCreatedBy(userId);
        window.setUpdatedBy(userId);
        window.setCreatedAt(now);
        window.setUpdatedAt(now);
        window.setVersion(1);
        return window;
    }

    private void applyRequest(MaintenanceOperation operation, MaintenanceOperationRequest request) {
        operation.setTitle(MaintenanceValues.normalizeRequired(request.title(), 255, "title"));
        operation.setDescription(
            MaintenanceValues.normalizeOptional(request.description(), 5000, "description")
        );
        operation.setStartAt(request.startAt());
        operation.setEndAt(request.endAt());
        operation.setSuppressAlerts(MaintenanceValues.defaultTrue(request.suppressAlerts()));
        operation.setExcludeFromAnalytics(
            MaintenanceValues.defaultTrue(request.excludeFromAnalytics())
        );
        operation.setExcludeFromMl(MaintenanceValues.defaultTrue(request.excludeFromMl()));
    }

    private void applyOperation(MaintenanceWindow window, MaintenanceOperation operation) {
        window.setTitle(operation.getTitle());
        window.setDescription(operation.getDescription());
        window.setStartAt(operation.getStartAt());
        window.setEndAt(operation.getEndAt());
        window.setSuppressAlerts(operation.isSuppressAlerts());
        window.setExcludeFromAnalytics(operation.isExcludeFromAnalytics());
        window.setExcludeFromMl(operation.isExcludeFromMl());
    }

    private void touch(MaintenanceOperation operation, UUID userId) {
        operation.setUpdatedBy(userId);
        operation.setUpdatedAt(Instant.now());
        operation.setVersion(operation.getVersion() + 1);
    }

    private void touch(MaintenanceWindow window, UUID userId) {
        window.setUpdatedBy(userId);
        window.setUpdatedAt(Instant.now());
        window.setVersion(window.getVersion() + 1);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SCHEDULED", "ACTIVE", "FINISHED", "CANCELLED" -> normalized;
            default -> throw new IllegalArgumentException(
                "Unknown maintenance operation status: " + status
            );
        };
    }
}
