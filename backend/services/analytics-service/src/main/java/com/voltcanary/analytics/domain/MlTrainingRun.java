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
@Table(name = "ml_training_runs")
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public UUID getFeatureSnapshotId() { return featureSnapshotId; }
    public void setFeatureSnapshotId(UUID featureSnapshotId) { this.featureSnapshotId = featureSnapshotId; }
    public MlModelFamily getModelFamily() { return modelFamily; }
    public void setModelFamily(MlModelFamily modelFamily) { this.modelFamily = modelFamily; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getHyperparametersJson() { return hyperparametersJson; }
    public void setHyperparametersJson(String hyperparametersJson) { this.hyperparametersJson = hyperparametersJson; }
    public MlTrainingRunStatus getStatus() { return status; }
    public void setStatus(MlTrainingRunStatus status) { this.status = status; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public String getLogsSummary() { return logsSummary; }
    public void setLogsSummary(String logsSummary) { this.logsSummary = logsSummary; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
