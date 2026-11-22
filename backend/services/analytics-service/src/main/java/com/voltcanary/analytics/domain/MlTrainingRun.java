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
@Table(name = "ml_training_runs")
@Getter
@Setter
public class MlTrainingRun {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Column(name = "feature_snapshot_id")
    private UUID featureSnapshotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_family", nullable = false)
    private MlModelFamily modelFamily;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "hyperparameters_json", nullable = false, columnDefinition = "text")
    private String hyperparametersJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlTrainingRunStatus status;

    @Column(name = "metrics_json", columnDefinition = "text")
    private String metricsJson;

    @Column(name = "artifact_uri")
    private String artifactUri;

    @Column(name = "logs_summary", columnDefinition = "text")
    private String logsSummary;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "stale_recovered_at")
    private Instant staleRecoveredAt;

    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;

    @Column(name = "lifecycle_actor_id")
    private UUID lifecycleActorId;

    @Column(name = "lifecycle_at")
    private Instant lifecycleAt;
}
