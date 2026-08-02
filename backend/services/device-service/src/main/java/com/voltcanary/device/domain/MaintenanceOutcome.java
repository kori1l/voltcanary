package com.voltcanary.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_outcomes")
public class MaintenanceOutcome {

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID operationId) { this.operationId = operationId; }
    public UUID getMaintenanceWindowId() { return maintenanceWindowId; }
    public void setMaintenanceWindowId(UUID maintenanceWindowId) { this.maintenanceWindowId = maintenanceWindowId; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public MaintenanceOutcomeType getOutcomeType() { return outcomeType; }
    public void setOutcomeType(MaintenanceOutcomeType outcomeType) { this.outcomeType = outcomeType; }
    public String getFailureMode() { return failureMode; }
    public void setFailureMode(String failureMode) { this.failureMode = failureMode; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public MaintenanceOutcomeSeverity getSeverity() { return severity; }
    public void setSeverity(MaintenanceOutcomeSeverity severity) { this.severity = severity; }
    public Integer getDowntimeMinutes() { return downtimeMinutes; }
    public void setDowntimeMinutes(Integer downtimeMinutes) { this.downtimeMinutes = downtimeMinutes; }
    public String getTechnicianNotes() { return technicianNotes; }
    public void setTechnicianNotes(String technicianNotes) { this.technicianNotes = technicianNotes; }
    public MaintenanceOutcomeSource getSource() { return source; }
    public void setSource(MaintenanceOutcomeSource source) { this.source = source; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
