package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.business.BusinessResourceAuthorization;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import com.voltcanary.device.event.MaintenanceWindowEventPublisher;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.MaintenanceOperationRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceWindowService {

    private final BusinessResourceAuthorization workspaceAccess;
    private final DeviceRepository workspaceDevices;

    private Device scopedDevice(
        AuthenticatedUser user,
        UUID workspace,
        UUID account,
        UUID deviceId,
        String action
    ) {
        workspaceAccess.device(user, account, workspace, action, deviceId);
        return workspaceDevices.findByIdAndAccountId(deviceId, account).orElseThrow();
    }

    @Transactional
    public MaintenanceWindowResponse workspaceCreate(
        AuthenticatedUser user,
        UUID workspace,
        UUID account,
        UUID deviceId,
        MaintenanceWindowRequest request
    ) {
        return createForDevice(
            user,
            scopedDevice(user, workspace, account, deviceId, "maintenance.write"),
            request
        );
    }

    @Transactional
    public MaintenanceWindowResponse workspaceCancel(
        AuthenticatedUser user,
        UUID workspace,
        UUID account,
        UUID deviceId,
        UUID id,
        CancelMaintenanceWindowRequest request
    ) {
        return cancelForDevice(
            user,
            scopedDevice(user, workspace, account, deviceId, "maintenance.write"),
            id,
            request
        );
    }

    @Transactional
    public MaintenanceWindowResponse workspaceUpdate(
        AuthenticatedUser user,
        UUID workspace,
        UUID account,
        UUID deviceId,
        UUID id,
        MaintenanceWindowRequest request
    ) {
        return updateForDevice(
            user,
            scopedDevice(user, workspace, account, deviceId, "maintenance.write"),
            id,
            request
        );
    }

    @Transactional
    public MaintenanceWindowResponse workspaceFinish(
        AuthenticatedUser user,
        UUID workspace,
        UUID account,
        UUID deviceId,
        UUID id
    ) {
        return finishForDevice(
            user,
            scopedDevice(user, workspace, account, deviceId, "maintenance.write"),
            id
        );
    }

    private final MaintenanceQueries queries;
    private final DeviceService deviceService;
    private final MaintenanceWindowOperationSync operationSync;
    private final MaintenanceOperationRepository operationRepository;
    private final MaintenanceWindowRepository repository;
    private final DeviceAuditService auditService;
    private final MaintenanceWindowEventPublisher eventPublisher;

    public MaintenanceWindowService(
        MaintenanceQueries queries,
        DeviceService deviceService,
        MaintenanceOperationRepository operationRepository,
        MaintenanceWindowRepository repository,
        DeviceAuditService auditService,
        MaintenanceWindowEventPublisher eventPublisher,
        BusinessResourceAuthorization workspaceAccess,
        DeviceRepository workspaceDevices
    ) {
        this.queries = queries;
        this.deviceService = deviceService;
        this.operationSync = new MaintenanceWindowOperationSync(repository, operationRepository);
        this.operationRepository = operationRepository;
        this.repository = repository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.workspaceAccess = workspaceAccess;
        this.workspaceDevices = workspaceDevices;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MaintenanceWindowResponse> list(
        AuthenticatedUser user,
        UUID deviceId,
        Instant from,
        Instant to,
        String status,
        Integer limit,
        Integer offset
    ) {
        Device device = deviceService.findOwned(user, deviceId);
        MaintenanceValues.validateQueryRange(from, to);
        String normalizedStatus = normalizeStatus(status);
        return queries
            .windows(
                MaintenanceWindowFilters.matching(
                    device.getAccountId(),
                    device.getId(),
                    from,
                    to,
                    true
                ),
                normalizedStatus,
                MaintenanceValues.normalizedOffset(offset),
                MaintenanceValues.normalizedLimit(limit)
            )
            .stream()
            .map(MaintenanceWindowViews::toResponse)
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MaintenanceWindowResponse get(AuthenticatedUser user, UUID deviceId, UUID windowId) {
        Device device = deviceService.findOwned(user, deviceId);
        return MaintenanceWindowViews.toResponse(loadOwned(device, windowId));
    }

    @Transactional
    public MaintenanceWindowResponse create(
        AuthenticatedUser user,
        UUID deviceId,
        MaintenanceWindowRequest request
    ) {
        return createForDevice(user, deviceService.findOwned(user, deviceId), request);
    }

    private MaintenanceWindowResponse createForDevice(
        AuthenticatedUser user,
        Device device,
        MaintenanceWindowRequest request
    ) {
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new IllegalArgumentException(
                "Decommissioned devices cannot receive maintenance windows"
            );
        }
        MaintenanceValues.validateRange(request.startAt(), request.endAt());
        Instant now = Instant.now();
        MaintenanceOperation operation = new MaintenanceOperation();
        operation.setId(UUID.randomUUID());
        operation.setAccountId(device.getAccountId());
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
        operation.setCreatedBy(user.id());
        operation.setUpdatedBy(user.id());
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        operation.setVersion(1);
        MaintenanceOperation savedOperation = operationRepository.save(operation);

        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(UUID.randomUUID());
        window.setAccountId(device.getAccountId());
        window.setDeviceId(device.getId());
        window.setOperationId(savedOperation.getId());
        applyRequest(window, request);
        window.setCreatedBy(user.id());
        window.setUpdatedBy(user.id());
        window.setCreatedAt(now);
        window.setUpdatedAt(now);
        window.setVersion(1);
        MaintenanceWindow saved = repository.save(window);
        auditService.record(
            user,
            savedOperation.getAccountId(),
            "MAINTENANCE_OPERATION_CREATED",
            "MAINTENANCE_OPERATION",
            savedOperation.getId()
        );
        auditService.record(
            user,
            saved.getAccountId(),
            "MAINTENANCE_WINDOW_CREATED",
            "MAINTENANCE_WINDOW",
            saved.getId()
        );
        eventPublisher.publish("MAINTENANCE_WINDOW_CREATED", saved, user.id());
        return MaintenanceWindowViews.toResponse(saved);
    }

    @Transactional
    public MaintenanceWindowResponse update(
        AuthenticatedUser user,
        UUID deviceId,
        UUID windowId,
        MaintenanceWindowRequest request
    ) {
        return updateForDevice(user, deviceService.findOwned(user, deviceId), windowId, request);
    }

    private MaintenanceWindowResponse updateForDevice(
        AuthenticatedUser user,
        Device device,
        UUID windowId,
        MaintenanceWindowRequest request
    ) {
        MaintenanceWindow window = loadOwned(device, windowId);
        if (window.getCancelledAt() != null) {
            throw new IllegalArgumentException("Cancelled maintenance windows cannot be updated");
        }
        if (MaintenanceValues.isFinished(window, Instant.now())) {
            throw new IllegalArgumentException("Finished maintenance windows cannot be updated");
        }
        MaintenanceValues.validateRange(request.startAt(), request.endAt());
        applyRequest(window, request);
        touch(window, user.id());
        MaintenanceWindow saved = repository.save(window);
        operationSync.synchronize(saved, user.id(), null);
        auditService.record(
            user,
            saved.getAccountId(),
            "MAINTENANCE_WINDOW_UPDATED",
            "MAINTENANCE_WINDOW",
            saved.getId()
        );
        eventPublisher.publish("MAINTENANCE_WINDOW_UPDATED", saved, user.id());
        return MaintenanceWindowViews.toResponse(saved);
    }

    @Transactional
    public MaintenanceWindowResponse cancel(
        AuthenticatedUser user,
        UUID deviceId,
        UUID windowId,
        CancelMaintenanceWindowRequest request
    ) {
        return cancelForDevice(user, deviceService.findOwned(user, deviceId), windowId, request);
    }

    private MaintenanceWindowResponse cancelForDevice(
        AuthenticatedUser user,
        Device device,
        UUID windowId,
        CancelMaintenanceWindowRequest request
    ) {
        MaintenanceWindow window = loadOwned(device, windowId);
        if (window.getCancelledAt() == null) {
            window.setCancelledAt(Instant.now());
            window.setCancellationReason(
                MaintenanceValues.normalizeOptional(
                    request == null ? null : request.reason(),
                    1000,
                    "reason"
                )
            );
            touch(window, user.id());
            repository.save(window);
            operationSync.synchronize(window, user.id(), window.getCancellationReason());
            auditService.record(
                user,
                window.getAccountId(),
                "MAINTENANCE_WINDOW_CANCELLED",
                "MAINTENANCE_WINDOW",
                window.getId()
            );
            eventPublisher.publish("MAINTENANCE_WINDOW_CANCELLED", window, user.id());
        }
        return MaintenanceWindowViews.toResponse(window);
    }

    @Transactional
    public MaintenanceWindowResponse finishNow(
        AuthenticatedUser user,
        UUID deviceId,
        UUID windowId
    ) {
        return finishForDevice(user, deviceService.findOwned(user, deviceId), windowId);
    }

    private MaintenanceWindowResponse finishForDevice(
        AuthenticatedUser user,
        Device device,
        UUID windowId
    ) {
        MaintenanceWindow window = loadOwned(device, windowId);
        Instant now = Instant.now();
        if (window.getCancelledAt() != null) {
            throw new IllegalArgumentException("Cancelled maintenance windows cannot be finished");
        }
        if (now.isBefore(window.getStartAt())) {
            throw new IllegalArgumentException(
                "Scheduled maintenance windows cannot be finished before they start"
            );
        }
        if (now.isBefore(window.getEndAt())) {
            window.setEndAt(now);
            touch(window, user.id());
            repository.save(window);
            operationSync.synchronize(window, user.id(), null);
            auditService.record(
                user,
                window.getAccountId(),
                "MAINTENANCE_WINDOW_FINISHED_NOW",
                "MAINTENANCE_WINDOW",
                window.getId()
            );
            eventPublisher.publish("MAINTENANCE_WINDOW_FINISHED", window, user.id());
        }
        return MaintenanceWindowViews.toResponse(window);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> listInternal(
        UUID deviceId,
        Instant from,
        Instant to,
        boolean includeCancelled
    ) {
        MaintenanceValues.validateQueryRange(from, to);
        return repository
            .findAll(
                MaintenanceWindowFilters.matching(null, deviceId, from, to, includeCancelled),
                Sort.by(Sort.Direction.ASC, "startAt")
            )
            .stream()
            .map(MaintenanceWindowViews::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> changes(Instant updatedAfter, Integer limit) {
        return changes(updatedAfter, null, limit);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> changes(
        Instant updatedAfter,
        UUID afterId,
        Integer limit
    ) {
        Instant checkpoint = updatedAfter == null ? Instant.EPOCH : updatedAfter;
        return repository
            .findChangesAfter(
                checkpoint,
                afterId,
                PageRequest.of(0, MaintenanceValues.normalizedLimit(limit))
            )
            .stream()
            .map(MaintenanceWindowViews::toResponse)
            .toList();
    }

    private MaintenanceWindow loadOwned(Device device, UUID windowId) {
        return repository
            .findByIdAndDeviceIdAndAccountId(windowId, device.getId(), device.getAccountId())
            .orElseThrow(() -> new NotFoundException("Maintenance window not found"));
    }

    private void applyRequest(MaintenanceWindow window, MaintenanceWindowRequest request) {
        window.setTitle(MaintenanceValues.normalizeRequired(request.title(), 255, "title"));
        window.setDescription(
            MaintenanceValues.normalizeOptional(request.description(), 5000, "description")
        );
        window.setStartAt(request.startAt());
        window.setEndAt(request.endAt());
        window.setSuppressAlerts(MaintenanceValues.defaultTrue(request.suppressAlerts()));
        window.setExcludeFromAnalytics(
            MaintenanceValues.defaultTrue(request.excludeFromAnalytics())
        );
        window.setExcludeFromMl(MaintenanceValues.defaultTrue(request.excludeFromMl()));
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
                "Unknown maintenance window status: " + status
            );
        };
    }
}
