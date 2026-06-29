package com.voltcanary.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "condition_type", nullable = false)
    private String conditionType;

    @Column(name = "threshold")
    private BigDecimal threshold;

    @Column(name = "threshold_unit")
    private String thresholdUnit;

    @Column(name = "baseline_multiplier")
    private BigDecimal baselineMultiplier;

    @Column(name = "min_points")
    private Integer minPoints;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds;

    @Column(name = "deduplication_window_seconds", nullable = false)
    private int deduplicationWindowSeconds;

    @Column(name = "notification_channel_ids_json", nullable = false, columnDefinition = "text")
    private String notificationChannelIdsJson;

    @Column(name = "config_json", nullable = false, columnDefinition = "text")
    private String configJson;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public void setScopeId(UUID scopeId) {
        this.scopeId = scopeId;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getConditionType() {
        return conditionType;
    }

    public void setConditionType(String conditionType) {
        this.conditionType = conditionType;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getThresholdUnit() {
        return thresholdUnit;
    }

    public void setThresholdUnit(String thresholdUnit) {
        this.thresholdUnit = thresholdUnit;
    }

    public BigDecimal getBaselineMultiplier() {
        return baselineMultiplier;
    }

    public void setBaselineMultiplier(BigDecimal baselineMultiplier) {
        this.baselineMultiplier = baselineMultiplier;
    }

    public Integer getMinPoints() {
        return minPoints;
    }

    public void setMinPoints(Integer minPoints) {
        this.minPoints = minPoints;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public int getDeduplicationWindowSeconds() {
        return deduplicationWindowSeconds;
    }

    public void setDeduplicationWindowSeconds(int deduplicationWindowSeconds) {
        this.deduplicationWindowSeconds = deduplicationWindowSeconds;
    }

    public String getNotificationChannelIdsJson() {
        return notificationChannelIdsJson;
    }

    public void setNotificationChannelIdsJson(String notificationChannelIdsJson) {
        this.notificationChannelIdsJson = notificationChannelIdsJson;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
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
