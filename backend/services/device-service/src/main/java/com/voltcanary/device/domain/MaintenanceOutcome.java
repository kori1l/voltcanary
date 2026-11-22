package com.voltcanary.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_outcomes")
@Getter
@Setter
public class MaintenanceOutcome {

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

    @Column(name = "maintenance_window_id")
    private UUID maintenanceWindowId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_type", nullable = false)
    private MaintenanceOutcomeType outcomeType;

    @Column(name = "failure_mode")
    private String failureMode;

    @Column(name = "component")
    private String component;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private MaintenanceOutcomeSeverity severity;

    @Column(name = "downtime_minutes")
    private Integer downtimeMinutes;

    @Column(name = "technician_notes", columnDefinition = "text")
    private String technicianNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private MaintenanceOutcomeSource source;

    @Column(name = "confidence", nullable = false)
    private double confidence;

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
