package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_outcomes_read_model")
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

    public UUID getMaintenanceOutcomeId() { return maintenanceOutcomeId; }
    public void setMaintenanceOutcomeId(UUID maintenanceOutcomeId) { this.maintenanceOutcomeId = maintenanceOutcomeId; }
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
    public String getOutcomeType() { return outcomeType; }
    public void setOutcomeType(String outcomeType) { this.outcomeType = outcomeType; }
    public String getFailureMode() { return failureMode; }
    public void setFailureMode(String failureMode) { this.failureMode = failureMode; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Integer getDowntimeMinutes() { return downtimeMinutes; }
    public void setDowntimeMinutes(Integer downtimeMinutes) { this.downtimeMinutes = downtimeMinutes; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
