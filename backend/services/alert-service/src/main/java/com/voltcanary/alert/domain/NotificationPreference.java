package com.voltcanary.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "severity_filter", nullable = false)
    private String severityFilter;

    @Column(name = "channel_type_filter", nullable = false)
    private String channelTypeFilter;

    @Column(name = "device_filter", nullable = false)
    private String deviceFilter;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "critical_bypass_quiet_hours", nullable = false)
    private boolean criticalBypassQuietHours;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSeverityFilter() {
        return severityFilter;
    }

    public void setSeverityFilter(String severityFilter) {
        this.severityFilter = severityFilter;
    }

    public String getChannelTypeFilter() {
        return channelTypeFilter;
    }

    public void setChannelTypeFilter(String channelTypeFilter) {
        this.channelTypeFilter = channelTypeFilter;
    }

    public String getDeviceFilter() {
        return deviceFilter;
    }

    public void setDeviceFilter(String deviceFilter) {
        this.deviceFilter = deviceFilter;
    }

    public LocalTime getQuietHoursStart() {
        return quietHoursStart;
    }

    public void setQuietHoursStart(LocalTime quietHoursStart) {
        this.quietHoursStart = quietHoursStart;
    }

    public LocalTime getQuietHoursEnd() {
        return quietHoursEnd;
    }

    public void setQuietHoursEnd(LocalTime quietHoursEnd) {
        this.quietHoursEnd = quietHoursEnd;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isCriticalBypassQuietHours() {
        return criticalBypassQuietHours;
    }

    public void setCriticalBypassQuietHours(boolean criticalBypassQuietHours) {
        this.criticalBypassQuietHours = criticalBypassQuietHours;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
