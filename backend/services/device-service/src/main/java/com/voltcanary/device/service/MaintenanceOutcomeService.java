package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.MaintenanceOutcome;
import com.voltcanary.device.domain.MaintenanceOutcomeSource;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.dto.MaintenanceOutcomeRequest;
import com.voltcanary.device.dto.MaintenanceOutcomeResponse;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.repository.MaintenanceOperationRepository;
import com.voltcanary.device.repository.MaintenanceOutcomeRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceOutcomeService {

    private static final int DEFAULT_CHANGE_LIMIT = 100;
    private static final int MAX_CHANGE_LIMIT = 500;

    private final AccountService accountService;
    private final DeviceService deviceService;
    private final MaintenanceOperationRepository operationRepository;
    private final MaintenanceWindowRepository windowRepository;
    private final MaintenanceOutcomeRepository outcomeRepository;
    private final DeviceAuditService auditService;

    public MaintenanceOutcomeService(
            AccountService accountService,
            DeviceService deviceService,
            MaintenanceOperationRepository operationRepository,
            MaintenanceWindowRepository windowRepository,
            MaintenanceOutcomeRepository outcomeRepository,
            DeviceAuditService auditService) {
        this.accountService = accountService;
        this.deviceService = deviceService;
        this.operationRepository = operationRepository;
        this.windowRepository = windowRepository;
        this.outcomeRepository = outcomeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceOutcomeResponse> list(AuthenticatedUser user, UUID operationId) {
        Account account = accountService.currentAccount(user);
        loadOperation(account.getId(), operationId);
        return outcomeRepository.findByAccountIdAndOperationIdOrderByObservedAtAsc(account.getId(), operationId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceOutcomeResponse get(AuthenticatedUser user, UUID outcomeId) {
        Account account = accountService.currentAccount(user);
        return toResponse(loadOwned(account.getId(), outcomeId));
    }

    @Transactional
    public MaintenanceOutcomeResponse create(AuthenticatedUser user, UUID operationId, MaintenanceOutcomeRequest request) {
        Account account = accountService.currentAccount(user);
        MaintenanceOperation operation = loadOperation(account.getId(), operationId);
        Device device = deviceService.findOwned(user, request.deviceId());
        MaintenanceWindow window = loadWindow(operation, device.getId(), request.maintenanceWindowId());
        validateRequest(request);

        Instant now = Instant.now();
        MaintenanceOutcome outcome = new MaintenanceOutcome();
        outcome.setId(UUID.randomUUID());
        outcome.setAccountId(account.getId());
        outcome.setDeviceId(device.getId());
        outcome.setOperationId(operation.getId());
        outcome.setMaintenanceWindowId(window == null ? null : window.getId());
        applyRequest(outcome, request);
        outcome.setCreatedBy(user.id());
        outcome.setUpdatedBy(user.id());
        outcome.setCreatedAt(now);
        outcome.setUpdatedAt(now);
        outcome.setVersion(1);

        MaintenanceOutcome saved = outcomeRepository.save(outcome);
        auditService.record(user, saved.getAccountId(), "MAINTENANCE_OUTCOME_CREATED", "MAINTENANCE_OUTCOME", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public MaintenanceOutcomeResponse update(AuthenticatedUser user, UUID outcomeId, MaintenanceOutcomeRequest request) {
        Account account = accountService.currentAccount(user);
        MaintenanceOutcome outcome = loadOwned(account.getId(), outcomeId);
        MaintenanceOperation operation = loadOperation(account.getId(), outcome.getOperationId());
        Device device = deviceService.findOwned(user, request.deviceId());
        MaintenanceWindow window = loadWindow(operation, device.getId(), request.maintenanceWindowId());
        validateRequest(request);

        outcome.setDeviceId(device.getId());
        outcome.setMaintenanceWindowId(window == null ? null : window.getId());
        applyRequest(outcome, request);
        outcome.setUpdatedBy(user.id());
        outcome.setUpdatedAt(Instant.now());
        outcome.setVersion(outcome.getVersion() + 1);

        MaintenanceOutcome saved = outcomeRepository.save(outcome);
        auditService.record(user, saved.getAccountId(), "MAINTENANCE_OUTCOME_UPDATED", "MAINTENANCE_OUTCOME", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceOutcomeResponse> changes(Instant updatedAfter, Integer limit) {
        Instant threshold = updatedAfter == null ? Instant.EPOCH : updatedAfter;
        return outcomeRepository.findChangesAfter(
                        threshold,
                        PageRequest.of(0, normalizedLimit(limit), Sort.by(Sort.Direction.ASC, "updatedAt")))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private MaintenanceOperation loadOperation(UUID accountId, UUID operationId) {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        return operationRepository.findByIdAndAccountId(operationId, accountId)
                .orElseThrow(() -> new NotFoundException("Maintenance operation not found"));
    }

    private MaintenanceOutcome loadOwned(UUID accountId, UUID outcomeId) {
        return outcomeRepository.findByIdAndAccountId(outcomeId, accountId)
                .orElseThrow(() -> new NotFoundException("Maintenance outcome not found"));
    }

    private MaintenanceWindow loadWindow(MaintenanceOperation operation, UUID deviceId, UUID maintenanceWindowId) {
        if (maintenanceWindowId == null) {
            return windowRepository.findByOperationIdOrderByStartAtAsc(operation.getId()).stream()
                    .filter(window -> deviceId.equals(window.getDeviceId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Device is not part of the maintenance operation"));
        }
        MaintenanceWindow window = windowRepository.findById(maintenanceWindowId)
                .orElseThrow(() -> new NotFoundException("Maintenance window not found"));
        if (!operation.getAccountId().equals(window.getAccountId())
                || !operation.getId().equals(window.getOperationId())
                || !deviceId.equals(window.getDeviceId())) {
            throw new NotFoundException("Maintenance window not found");
        }
        return window;
    }

    private void applyRequest(MaintenanceOutcome outcome, MaintenanceOutcomeRequest request) {
        outcome.setObservedAt(request.observedAt());
        outcome.setResolvedAt(request.resolvedAt());
        outcome.setOutcomeType(request.outcomeType());
        outcome.setFailureMode(normalizeOptional(request.failureMode(), 255, "failureMode"));
        outcome.setComponent(normalizeOptional(request.component(), 255, "component"));
        outcome.setSeverity(request.severity());
        outcome.setDowntimeMinutes(request.downtimeMinutes());
        outcome.setTechnicianNotes(normalizeOptional(request.technicianNotes(), 5000, "technicianNotes"));
        outcome.setSource(request.source() == null ? MaintenanceOutcomeSource.OPERATOR : request.source());
        outcome.setConfidence(request.confidence() == null ? 1.0 : request.confidence());
    }

    private void validateRequest(MaintenanceOutcomeRequest request) {
        if (request.observedAt() == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
        if (request.outcomeType() == null) {
            throw new IllegalArgumentException("outcomeType is required");
        }
        if (request.resolvedAt() != null && request.resolvedAt().isBefore(request.observedAt())) {
            throw new IllegalArgumentException("resolvedAt must not be before observedAt");
        }
        if (request.downtimeMinutes() != null && request.downtimeMinutes() < 0) {
            throw new IllegalArgumentException("downtimeMinutes must be greater than or equal to 0");
        }
        double confidence = request.confidence() == null ? 1.0 : request.confidence();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private MaintenanceOutcomeResponse toResponse(MaintenanceOutcome outcome) {
        return new MaintenanceOutcomeResponse(
                outcome.getId(),
                outcome.getAccountId(),
                outcome.getDeviceId(),
                outcome.getOperationId(),
                outcome.getMaintenanceWindowId(),
                outcome.getObservedAt(),
                outcome.getResolvedAt(),
                outcome.getOutcomeType(),
                outcome.getFailureMode(),
                outcome.getComponent(),
                outcome.getSeverity(),
                outcome.getDowntimeMinutes(),
                outcome.getTechnicianNotes(),
                outcome.getSource(),
                outcome.getConfidence(),
                outcome.getCreatedBy(),
                outcome.getUpdatedBy(),
                outcome.getCreatedAt(),
                outcome.getUpdatedAt(),
                outcome.getVersion());
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

    private int normalizedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_CHANGE_LIMIT;
        }
        if (limit < 1 || limit > MAX_CHANGE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CHANGE_LIMIT);
        }
        return limit;
    }
}
