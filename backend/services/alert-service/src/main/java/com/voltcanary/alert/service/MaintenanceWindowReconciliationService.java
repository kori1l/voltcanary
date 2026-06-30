package com.voltcanary.alert.service;

import com.voltcanary.alert.config.MaintenanceReconciliationProperties;
import com.voltcanary.alert.dto.MaintenanceWindowSnapshot;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "app.maintenance.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceWindowReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowReconciliationService.class);
    private static final String LOCK_NAME = "alert-maintenance-reconciliation";
    private static final String CHECKPOINT_NAME = "device-service-maintenance";

    private final MaintenanceReconciliationProperties properties;
    private final SchedulerLockService schedulerLockService;
    private final MaintenanceWindowDeviceClient deviceClient;
    private final MaintenanceWindowProjectionService projectionService;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public MaintenanceWindowReconciliationService(
            MaintenanceReconciliationProperties properties,
            SchedulerLockService schedulerLockService,
            MaintenanceWindowDeviceClient deviceClient,
            MaintenanceWindowProjectionService projectionService,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.schedulerLockService = schedulerLockService;
        this.deviceClient = deviceClient;
        this.projectionService = projectionService;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

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
        Instant checkpoint = checkpoint();
        List<MaintenanceWindowSnapshot> changes = deviceClient.changes(checkpoint, properties.getBatchSize());
        int applied = 0;
        for (MaintenanceWindowSnapshot snapshot : changes) {
            if (projectionService.upsertSnapshot(snapshot)) {
                applied++;
            }
        }
        changes.stream()
                .map(MaintenanceWindowSnapshot::updatedAt)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .ifPresent(this::saveCheckpoint);
        meterRegistry.counter("alert.maintenance.reconciliation.completed", "status", "success").increment();
        return new ReconciliationResult(changes.size(), applied);
    }

    private Instant checkpoint() {
        List<Instant> values = jdbcTemplate.query("""
                select checkpoint_at
                from maintenance_projection_checkpoints
                where source_name = ?
                """, (rs, rowNum) -> rs.getTimestamp("checkpoint_at").toInstant(), CHECKPOINT_NAME);
        return values.isEmpty() ? Instant.EPOCH : values.getFirst();
    }

    @Transactional
    public void saveCheckpoint(Instant checkpoint) {
        int updated = jdbcTemplate.update("""
                update maintenance_projection_checkpoints
                set checkpoint_at = ?, updated_at = ?
                where source_name = ?
                """, Timestamp.from(checkpoint), Timestamp.from(Instant.now()), CHECKPOINT_NAME);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into maintenance_projection_checkpoints (source_name, checkpoint_at, updated_at)
                    values (?, ?, ?)
                    """, CHECKPOINT_NAME, Timestamp.from(checkpoint), Timestamp.from(Instant.now()));
        }
    }

    public record ReconciliationResult(int fetched, int applied) {
    }
}
