package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_deployment_policies")
public class MlDeploymentPolicy {
    @Id
    private UUID id;
    @Column(name = "account_id")
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private MlDeploymentScope scope;
    @Column(name = "device_id")
    private UUID deviceId;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "model_version_id")
    private UUID modelVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private MlDeploymentMode mode;
    @Column(name = "canary_percent")
    private Integer canaryPercent;
    @Column(name = "fallback_enabled", nullable = false)
    private boolean fallbackEnabled;
    @Column(name = "updated_by")
    private UUID updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public MlDeploymentScope getScope() { return scope; }
    public void setScope(MlDeploymentScope scope) { this.scope = scope; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public UUID getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(UUID modelVersionId) { this.modelVersionId = modelVersionId; }
    public MlDeploymentMode getMode() { return mode; }
    public void setMode(MlDeploymentMode mode) { this.mode = mode; }
    public Integer getCanaryPercent() { return canaryPercent; }
    public void setCanaryPercent(Integer canaryPercent) { this.canaryPercent = canaryPercent; }
    public boolean isFallbackEnabled() { return fallbackEnabled; }
    public void setFallbackEnabled(boolean fallbackEnabled) { this.fallbackEnabled = fallbackEnabled; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
