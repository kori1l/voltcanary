package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import com.voltcanary.device.event.MaintenanceWindowEventPublisher;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.repository.MaintenanceOperationRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceWindowService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final DeviceService deviceService;
    private final MaintenanceOperationRepository operationRepository;
    private final MaintenanceWindowRepository repository;
    private final DeviceAuditService auditService;
    private final MaintenanceWindowEventPublisher eventPublisher;

    public MaintenanceWindowService(
            DeviceService deviceService,
            MaintenanceOperationRepository operationRepository,
            MaintenanceWindowRepository repository,
            DeviceAuditService auditService,
            MaintenanceWindowEventPublisher eventPublisher) {
        this.deviceService = deviceService;
        this.operationRepository = operationRepository;
        this.repository = repository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> list(
            AuthenticatedUser user,
            UUID deviceId,
            Instant from,
            Instant to,
            String status,
            Integer limit,
            Integer offset) {
        Device device = deviceService.findOwned(user, deviceId);
        validateQueryRange(from, to);
        String normalizedStatus = normalizeStatus(status);
        return repository.findAll(
                        maintenanceWindowSpec(device.getAccountId(), device.getId(), from, to, true),
                        PageRequest.of(
                                0,
                                normalizedOffset(offset) + normalizedLimit(limit),
                                Sort.by(Sort.Direction.DESC, "startAt")))
                .getContent()
                .stream()
                .skip(normalizedOffset(offset))
                .map(this::toResponse)
                .filter(response -> normalizedStatus == null || normalizedStatus.equals(response.status()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceWindowResponse get(AuthenticatedUser user, UUID deviceId, UUID windowId) {
        Device device = deviceService.findOwned(user, deviceId);
        return toResponse(loadOwned(device, windowId));
    }

    @Transactional
    public MaintenanceWindowResponse create(AuthenticatedUser user, UUID deviceId, MaintenanceWindowRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new IllegalArgumentException("Decommissioned devices cannot receive maintenance windows");
        }
        validateRange(request.startAt(), request.endAt());
        Instant now = Instant.now();
        MaintenanceOperation operation = new MaintenanceOperation();
        operation.setId(UUID.randomUUID());
        operation.setAccountId(device.getAccountId());
        operation.setTitle(normalizeRequired(request.title(), 255, "title"));
        operation.setDescription(normalizeOptional(request.description(), 5000, "description"));
        operation.setStartAt(request.startAt());
        operation.setEndAt(request.endAt());
        operation.setSuppressAlerts(defaultTrue(request.suppressAlerts()));
        operation.setExcludeFromAnalytics(defaultTrue(request.excludeFromAnalytics()));
        operation.setExcludeFromMl(defaultTrue(request.excludeFromMl()));
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
        auditService.record(user, savedOperation.getAccountId(), "MAINTENANCE_OPERATION_CREATED", "MAINTENANCE_OPERATION", savedOperation.getId());
        auditService.record(user, saved.getAccountId(), "MAINTENANCE_WINDOW_CREATED", "MAINTENANCE_WINDOW", saved.getId());
        eventPublisher.publish("MAINTENANCE_WINDOW_CREATED", saved, user.id());
        return toResponse(saved);
    }

    @Transactional
    public MaintenanceWindowResponse update(
            AuthenticatedUser user,
            UUID deviceId,
            UUID windowId,
            MaintenanceWindowRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        MaintenanceWindow window = loadOwned(device, windowId);
        if (window.getCancelledAt() != null) {
            throw new IllegalArgumentException("Cancelled maintenance windows cannot be updated");
        }
        if (isFinished(window, Instant.now())) {
            throw new IllegalArgumentException("Finished maintenance windows cannot be updated");
        }
        validateRange(request.startAt(), request.endAt());
        applyRequest(window, request);
        touch(window, user.id());
        MaintenanceWindow saved = repository.save(window);
        syncSingleWindowOperation(saved, user.id(), null);
        auditService.record(user, saved.getAccountId(), "MAINTENANCE_WINDOW_UPDATED", "MAINTENANCE_WINDOW", saved.getId());
        eventPublisher.publish("MAINTENANCE_WINDOW_UPDATED", saved, user.id());
        return toResponse(saved);
    }

    @Transactional
    public MaintenanceWindowResponse cancel(
            AuthenticatedUser user,
            UUID deviceId,
            UUID windowId,
            CancelMaintenanceWindowRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        MaintenanceWindow window = loadOwned(device, windowId);
        if (window.getCancelledAt() == null) {
            window.setCancelledAt(Instant.now());
            window.setCancellationReason(normalizeOptional(request == null ? null : request.reason(), 1000, "reason"));
            touch(window, user.id());
            repository.save(window);
            syncSingleWindowOperation(window, user.id(), window.getCancellationReason());
            auditService.record(user, window.getAccountId(), "MAINTENANCE_WINDOW_CANCELLED", "MAINTENANCE_WINDOW", window.getId());
            eventPublisher.publish("MAINTENANCE_WINDOW_CANCELLED", window, user.id());
        }
        return toResponse(window);
    }

    @Transactional
    public MaintenanceWindowResponse finishNow(AuthenticatedUser user, UUID deviceId, UUID windowId) {
        Device device = deviceService.findOwned(user, deviceId);
        MaintenanceWindow window = loadOwned(device, windowId);
        Instant now = Instant.now();
        if (window.getCancelledAt() != null) {
            throw new IllegalArgumentException("Cancelled maintenance windows cannot be finished");
        }
        if (now.isBefore(window.getStartAt())) {
            throw new IllegalArgumentException("Scheduled maintenance windows cannot be finished before they start");
        }
        if (now.isBefore(window.getEndAt())) {
            window.setEndAt(now);
            touch(window, user.id());
            repository.save(window);
            syncSingleWindowOperation(window, user.id(), null);
            auditService.record(user, window.getAccountId(), "MAINTENANCE_WINDOW_FINISHED_NOW", "MAINTENANCE_WINDOW", window.getId());
            eventPublisher.publish("MAINTENANCE_WINDOW_FINISHED", window, user.id());
        }
        return toResponse(window);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> listInternal(
            UUID deviceId,
            Instant from,
            Instant to,
            boolean includeCancelled) {
        validateQueryRange(from, to);
        return repository.findAll(
                        maintenanceWindowSpec(null, deviceId, from, to, includeCancelled),
                        Sort.by(Sort.Direction.ASC, "startAt"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> changes(Instant updatedAfter, Integer limit) {
        Instant checkpoint = updatedAfter == null ? Instant.EPOCH : updatedAfter;
        return repository.findChangesAfter(checkpoint, PageRequest.of(0, normalizedLimit(limit))).stream()
                .map(this::toResponse)
                .toList();
    }

    private MaintenanceWindow loadOwned(Device device, UUID windowId) {
        return repository.findByIdAndDeviceIdAndAccountId(windowId, device.getId(), device.getAccountId())
                .orElseThrow(() -> new NotFoundException("Maintenance window not found"));
    }

    private void applyRequest(MaintenanceWindow window, MaintenanceWindowRequest request) {
        window.setTitle(normalizeRequired(request.title(), 255, "title"));
        window.setDescription(normalizeOptional(request.description(), 5000, "description"));
        window.setStartAt(request.startAt());
        window.setEndAt(request.endAt());
        window.setSuppressAlerts(defaultTrue(request.suppressAlerts()));
        window.setExcludeFromAnalytics(defaultTrue(request.excludeFromAnalytics()));
        window.setExcludeFromMl(defaultTrue(request.excludeFromMl()));
    }

    private void touch(MaintenanceWindow window, UUID userId) {
        window.setUpdatedBy(userId);
        window.setUpdatedAt(Instant.now());
        window.setVersion(window.getVersion() + 1);
    }

    private void syncSingleWindowOperation(MaintenanceWindow window, UUID userId, String cancellationReason) {
        if (window.getOperationId() == null) {
            return;
        }
        List<MaintenanceWindow> operationWindows = repository.findByOperationIdOrderByStartAtAsc(window.getOperationId());
        if (operationWindows.size() != 1) {
            return;
        }
        operationRepository.findById(window.getOperationId()).ifPresent(operation -> {
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

    private MaintenanceWindowResponse toResponse(MaintenanceWindow window) {
        return new MaintenanceWindowResponse(
                window.getId(),
                window.getAccountId(),
                window.getDeviceId(),
                window.getOperationId(),
                window.getTitle(),
                window.getDescription(),
                window.getStartAt(),
                window.getEndAt(),
                status(window, Instant.now()),
                window.isSuppressAlerts(),
                window.isExcludeFromAnalytics(),
                window.isExcludeFromMl(),
                window.getCancelledAt(),
                window.getCancellationReason(),
                window.getCreatedBy(),
                window.getUpdatedBy(),
                window.getCreatedAt(),
                window.getUpdatedAt(),
                window.getVersion());
    }

    private String status(MaintenanceWindow window, Instant now) {
        if (window.getCancelledAt() != null) {
            return "CANCELLED";
        }
        if (now.isBefore(window.getStartAt())) {
            return "SCHEDULED";
        }
        if (now.isBefore(window.getEndAt())) {
            return "ACTIVE";
        }
        return "FINISHED";
    }

    private boolean isFinished(MaintenanceWindow window, Instant now) {
        return window.getCancelledAt() == null && !now.isBefore(window.getEndAt());
    }

    private Specification<MaintenanceWindow> maintenanceWindowSpec(
            UUID accountId,
            UUID deviceId,
            Instant from,
            Instant to,
            boolean includeCancelled) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (accountId != null) {
                predicates.add(criteriaBuilder.equal(root.get("accountId"), accountId));
            }
            predicates.add(criteriaBuilder.equal(root.get("deviceId"), deviceId));
            if (!includeCancelled) {
                predicates.add(criteriaBuilder.isNull(root.get("cancelledAt")));
            }
            if (from != null) {
                predicates.add(criteriaBuilder.greaterThan(root.get("endAt"), from));
            }
            if (to != null) {
                predicates.add(criteriaBuilder.lessThan(root.get("startAt"), to));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void validateRange(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new IllegalArgumentException("startAt and endAt are required");
        }
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
    }

    private void validateQueryRange(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
    }

    private String normalizeRequired(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value, maxLength, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SCHEDULED", "ACTIVE", "FINISHED", "CANCELLED" -> normalized;
            default -> throw new IllegalArgumentException("Unknown maintenance window status: " + status);
        };
    }

    private int normalizedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private int normalizedOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        return offset;
    }

    private boolean defaultTrue(Boolean value) {
        return value == null || value;
    }
}
