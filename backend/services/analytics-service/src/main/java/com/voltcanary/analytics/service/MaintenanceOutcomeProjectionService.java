package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.dto.MaintenanceOutcomeSnapshot;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceOutcomeProjectionService {

    private final MaintenanceOutcomeReadModelRepository repository;
    private final MeterRegistry meterRegistry;

    public MaintenanceOutcomeProjectionService(
            MaintenanceOutcomeReadModelRepository repository,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public boolean upsertSnapshot(MaintenanceOutcomeSnapshot snapshot) {
        MaintenanceOutcomeReadModel entity = repository.findById(snapshot.id())
                .orElseGet(MaintenanceOutcomeReadModel::new);
        if (entity.getMaintenanceOutcomeId() != null && entity.getVersion() >= snapshot.version()) {
            meterRegistry.counter("analytics.maintenance.outcome.reconciliation.projected", "status", "stale").increment();
            return false;
        }
        entity.setMaintenanceOutcomeId(snapshot.id());
        entity.setAccountId(snapshot.accountId());
        entity.setDeviceId(snapshot.deviceId());
        entity.setOperationId(snapshot.operationId());
        entity.setMaintenanceWindowId(snapshot.maintenanceWindowId());
        entity.setObservedAt(snapshot.observedAt());
        entity.setResolvedAt(snapshot.resolvedAt());
        entity.setOutcomeType(snapshot.outcomeType());
        entity.setFailureMode(snapshot.failureMode());
        entity.setComponent(snapshot.component());
        entity.setSeverity(snapshot.severity());
        entity.setDowntimeMinutes(snapshot.downtimeMinutes());
        entity.setSource(snapshot.source());
        entity.setConfidence(snapshot.confidence());
        entity.setVersion(snapshot.version());
        entity.setUpdatedAt(snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt());
        repository.save(entity);
        meterRegistry.counter("analytics.maintenance.outcome.reconciliation.projected", "status", "applied").increment();
        return true;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceOutcomeReadModel> observedForDevice(UUID deviceId, Instant from, Instant to) {
        return repository.findByDeviceAndObservedRange(deviceId, from, to);
    }
}
