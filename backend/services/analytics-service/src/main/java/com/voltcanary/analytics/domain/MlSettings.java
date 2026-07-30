package com.voltcanary.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_settings")
public class MlSettings {
    @Id private UUID id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "device_id") private UUID deviceId;
    @Column(name = "ml_enabled", nullable = false) private boolean mlEnabled;
    @Enumerated(EnumType.STRING) @Column(name = "prediction_mode", nullable = false) private PredictionMode predictionMode;
    @Column(name = "confidence_warning_threshold", nullable = false) private double confidenceWarningThreshold;
    @Enumerated(EnumType.STRING) @Column(name = "maintenance_behavior", nullable = false) private MaintenanceMlBehavior maintenanceBehavior;
    @Column(name = "explanations_visible", nullable = false) private boolean explanationsVisible;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "updated_by") private UUID updatedBy;

    public UUID getId() { return id; } public void setId(UUID value) { id = value; }
    public UUID getAccountId() { return accountId; } public void setAccountId(UUID value) { accountId = value; }
    public UUID getDeviceId() { return deviceId; } public void setDeviceId(UUID value) { deviceId = value; }
    public boolean isMlEnabled() { return mlEnabled; } public void setMlEnabled(boolean value) { mlEnabled = value; }
    public PredictionMode getPredictionMode() { return predictionMode; } public void setPredictionMode(PredictionMode value) { predictionMode = value; }
    public double getConfidenceWarningThreshold() { return confidenceWarningThreshold; } public void setConfidenceWarningThreshold(double value) { confidenceWarningThreshold = value; }
    public MaintenanceMlBehavior getMaintenanceBehavior() { return maintenanceBehavior; } public void setMaintenanceBehavior(MaintenanceMlBehavior value) { maintenanceBehavior = value; }
    public boolean isExplanationsVisible() { return explanationsVisible; } public void setExplanationsVisible(boolean value) { explanationsVisible = value; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant value) { updatedAt = value; }
    public UUID getUpdatedBy() { return updatedBy; } public void setUpdatedBy(UUID value) { updatedBy = value; }
}
