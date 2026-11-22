package com.voltcanary.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_windows")
@Getter
@Setter
public class MaintenanceWindow {

    @jakarta.persistence.Version
    @Column(name = "row_revision", nullable = false)
    private Long rowRevision;

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "suppress_alerts", nullable = false)
    private boolean suppressAlerts;

    @Column(name = "exclude_from_analytics", nullable = false)
    private boolean excludeFromAnalytics;

    @Column(name = "exclude_from_ml", nullable = false)
    private boolean excludeFromMl;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private long version;
}
