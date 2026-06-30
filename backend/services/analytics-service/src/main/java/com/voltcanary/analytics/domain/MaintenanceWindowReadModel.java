package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_windows_read_model")
public class MaintenanceWindowReadModel {

    @Id
    @Column(name = "maintenance_window_id")
    private UUID maintenanceWindowId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String title;

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

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getMaintenanceWindowId() {
        return maintenanceWindowId;
    }

    public void setMaintenanceWindowId(UUID maintenanceWindowId) {
        this.maintenanceWindowId = maintenanceWindowId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public boolean isSuppressAlerts() {
        return suppressAlerts;
    }

    public void setSuppressAlerts(boolean suppressAlerts) {
        this.suppressAlerts = suppressAlerts;
    }

    public boolean isExcludeFromAnalytics() {
        return excludeFromAnalytics;
    }

    public void setExcludeFromAnalytics(boolean excludeFromAnalytics) {
        this.excludeFromAnalytics = excludeFromAnalytics;
    }

    public boolean isExcludeFromMl() {
        return excludeFromMl;
    }

    public void setExcludeFromMl(boolean excludeFromMl) {
        this.excludeFromMl = excludeFromMl;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
