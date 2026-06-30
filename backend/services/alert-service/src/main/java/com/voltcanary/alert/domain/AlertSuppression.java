package com.voltcanary.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_suppressions")
public class AlertSuppression {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "source_event_type", nullable = false)
    private String sourceEventType;

    @Column(name = "maintenance_window_id", nullable = false)
    private UUID maintenanceWindowId;

    @Column(name = "maintenance_window_version", nullable = false)
    private long maintenanceWindowVersion;

    @Column(name = "suppression_reason", nullable = false)
    private String suppressionReason;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getAlertId() {
        return alertId;
    }

    public void setAlertId(UUID alertId) {
        this.alertId = alertId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(UUID sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getSourceEventType() {
        return sourceEventType;
    }

    public void setSourceEventType(String sourceEventType) {
        this.sourceEventType = sourceEventType;
    }

    public UUID getMaintenanceWindowId() {
        return maintenanceWindowId;
    }

    public void setMaintenanceWindowId(UUID maintenanceWindowId) {
        this.maintenanceWindowId = maintenanceWindowId;
    }

    public long getMaintenanceWindowVersion() {
        return maintenanceWindowVersion;
    }

    public void setMaintenanceWindowVersion(long maintenanceWindowVersion) {
        this.maintenanceWindowVersion = maintenanceWindowVersion;
    }

    public String getSuppressionReason() {
        return suppressionReason;
    }

    public void setSuppressionReason(String suppressionReason) {
        this.suppressionReason = suppressionReason;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
