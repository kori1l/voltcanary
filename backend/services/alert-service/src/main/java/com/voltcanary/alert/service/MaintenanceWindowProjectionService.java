package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.MaintenanceWindowReadModel;
import com.voltcanary.alert.dto.MaintenanceWindowSnapshot;
import com.voltcanary.alert.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceWindowProjectionService {

    private static final String SOURCE_TOPIC = "maintenance-window.changed.v1";

    private final ProcessedEventService processedEventService;
    private final MaintenanceWindowReadModelRepository repository;
    private final AlertMaintenanceSuppressionService suppressionService;
    private final MeterRegistry meterRegistry;

    public MaintenanceWindowProjectionService(
            ProcessedEventService processedEventService,
            MaintenanceWindowReadModelRepository repository,
            AlertMaintenanceSuppressionService suppressionService,
            MeterRegistry meterRegistry) {
        this.processedEventService = processedEventService;
        this.repository = repository;
        this.suppressionService = suppressionService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public boolean project(MaintenanceWindowChangedEvent event) {
        if (!processedEventService.markIfNew(SOURCE_TOPIC, event.eventId())) {
            return false;
        }
        MaintenanceWindowReadModel entity = repository.findById(event.maintenanceWindowId())
                .orElseGet(MaintenanceWindowReadModel::new);
        if (entity.getMaintenanceWindowId() != null && entity.getVersion() >= event.version()) {
            meterRegistry.counter("alert.maintenance.events_projected", "status", "stale").increment();
            return true;
        }
        apply(entity, event);
        MaintenanceWindowReadModel saved = repository.save(entity);
        suppressionService.autoResolveOverlappingAlerts(saved);
        meterRegistry.counter("alert.maintenance.events_projected", "status", "applied").increment();
        return true;
    }

    @Transactional
    public boolean upsertSnapshot(MaintenanceWindowSnapshot snapshot) {
        MaintenanceWindowReadModel entity = repository.findById(snapshot.id())
                .orElseGet(MaintenanceWindowReadModel::new);
        if (entity.getMaintenanceWindowId() != null && entity.getVersion() >= snapshot.version()) {
            meterRegistry.counter("alert.maintenance.reconciliation.projected", "status", "stale").increment();
            return false;
        }
        apply(entity, snapshot);
        MaintenanceWindowReadModel saved = repository.save(entity);
        suppressionService.autoResolveOverlappingAlerts(saved);
        meterRegistry.counter("alert.maintenance.reconciliation.projected", "status", "applied").increment();
        return true;
    }

    private void apply(MaintenanceWindowReadModel entity, MaintenanceWindowChangedEvent event) {
        entity.setMaintenanceWindowId(event.maintenanceWindowId());
        entity.setAccountId(event.accountId());
        entity.setDeviceId(event.deviceId());
        entity.setTitle(event.title());
        entity.setStartAt(event.startAt());
        entity.setEndAt(event.endAt());
        entity.setSuppressAlerts(event.suppressAlerts());
        entity.setExcludeFromAnalytics(event.excludeFromAnalytics());
        entity.setExcludeFromMl(event.excludeFromMl());
        entity.setCancelledAt(event.cancelledAt());
        entity.setVersion(event.version());
        entity.setUpdatedAt(event.occurredAt() == null ? Instant.now() : event.occurredAt());
    }

    private void apply(MaintenanceWindowReadModel entity, MaintenanceWindowSnapshot snapshot) {
        entity.setMaintenanceWindowId(snapshot.id());
        entity.setAccountId(snapshot.accountId());
        entity.setDeviceId(snapshot.deviceId());
        entity.setTitle(snapshot.title());
        entity.setStartAt(snapshot.startAt());
        entity.setEndAt(snapshot.endAt());
        entity.setSuppressAlerts(snapshot.suppressAlerts());
        entity.setExcludeFromAnalytics(snapshot.excludeFromAnalytics());
        entity.setExcludeFromMl(snapshot.excludeFromMl());
        entity.setCancelledAt(snapshot.cancelledAt());
        entity.setVersion(snapshot.version());
        entity.setUpdatedAt(snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt());
    }
}
