package com.voltcanary.analytics.domain;

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
@Table(name = "ml_deployment_policies")
@Getter
@Setter
public class MlDeploymentPolicy {

    @Column(name = "active_scope_key")
    private String activeScopeKey;

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    private void updateScopeKey() {
        activeScopeKey =
            deletedAt != null
                ? null
                : switch (scope) {
                      case SYSTEM -> "SYSTEM";
                      case ACCOUNT -> "ACCOUNT:" + accountId;
                      case DEVICE_TYPE -> "DEVICE_TYPE:" + accountId + ":" + deviceType;
                      case DEVICE -> "DEVICE:" + deviceId;
                  };
    }

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

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "deletion_reason", columnDefinition = "text")
    private String deletionReason;
}
