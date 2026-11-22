package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_outcomes_read_model")
@Getter
@Setter
public class MaintenanceOutcomeReadModel {

    @Id
    @Column(name = "maintenance_outcome_id")
    private UUID maintenanceOutcomeId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "maintenance_window_id")
    private UUID maintenanceWindowId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "outcome_type", nullable = false)
    private String outcomeType;

    @Column(name = "failure_mode")
    private String failureMode;

    @Column(name = "component")
    private String component;

    @Column(name = "severity")
    private String severity;

    @Column(name = "downtime_minutes")
    private Integer downtimeMinutes;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
