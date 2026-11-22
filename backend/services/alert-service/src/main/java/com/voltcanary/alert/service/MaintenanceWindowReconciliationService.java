package com.voltcanary.alert.service;

import com.voltcanary.alert.config.MaintenanceReconciliationProperties;
import com.voltcanary.alert.dto.MaintenanceWindowSnapshot;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.maintenance.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MaintenanceWindowReconciliationService {

    private static final String LOCK_NAME = "alert-maintenance-reconciliation";
    private static final String CHECKPOINT_NAME = "device-service-maintenance";

    private final MaintenanceReconciliationProperties properties;
    private final SchedulerLockService schedulerLockService;
    private final MaintenanceWindowDeviceClient deviceClient;
    private final MaintenanceWindowProjectionService projectionService;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "#{@maintenanceReconciliationProperties.interval.toMillis()}")
    public void reconcileScheduled() {
        try {
            if (!schedulerLockService.acquire(LOCK_NAME, properties.getLockLease())) {
                return;
            }
        } catch (DataAccessException exception) {
            meterRegistry.counter("alert.maintenance.reconciliation.completed", "status", "lock_unavailable").increment();
            log.warn("alertMaintenanceReconciliationSkipped reason=lock_unavailable");
            return;
        }
        try {
            ReconciliationResult result = reconcile();
            log.info("alertMaintenanceReconciliation fetched={} applied={}", result.fetched(), result.applied());
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }

    public ReconciliationResult reconcile() {
        Cursor checkpoint = checkpoint(CHECKPOINT_NAME);
        List<MaintenanceWindowSnapshot> changes = deviceClient.changes(checkpoint.at(), checkpoint.id(), properties.getBatchSize());
        int applied = 0;
        for (MaintenanceWindowSnapshot snapshot : changes) {
            if (projectionService.upsertSnapshot(snapshot)) {
                applied++;
            }
        }
        if (!changes.isEmpty()) {
            var last = changes.getLast();
            saveCheckpoint(CHECKPOINT_NAME, last.updatedAt(), last.id());
        }
        meterRegistry.counter("alert.maintenance.reconciliation.completed", "status", "success").increment();
        return new ReconciliationResult(changes.size(), applied);
    }

    private record Cursor(Instant at, UUID id) { }

    private Cursor checkpoint(String name) {
        return jdbcTemplate.query("select checkpoint_at, checkpoint_id from maintenance_projection_checkpoints where source_name=?",
                (rs, index) -> new Cursor(rs.getTimestamp(1).toInstant(), rs.getObject(2, UUID.class)), name)
                .stream().findFirst().orElse(new Cursor(Instant.EPOCH, null));
    }

    private void saveCheckpoint(String name, Instant at, UUID id) {
        int updated = jdbcTemplate.update("update maintenance_projection_checkpoints set checkpoint_at=?, checkpoint_id=?, updated_at=? where source_name=?",
                Timestamp.from(at), id, Timestamp.from(Instant.now()), name);
        if (updated == 0) {
            jdbcTemplate.update("insert into maintenance_projection_checkpoints(source_name, checkpoint_at, checkpoint_id, updated_at) values(?,?,?,?)",
                    name, Timestamp.from(at), id, Timestamp.from(Instant.now()));
        }
    }

    public record ReconciliationResult(int fetched, int applied) {
    }
}
