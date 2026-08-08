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
@Table(name = "ml_dataset_versions")
public class MlDatasetVersion {
    @Id
    private UUID id;
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "version", nullable = false)
    private int version;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlDatasetStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "split_strategy", nullable = false)
    private MlTrainingSplitStrategy splitStrategy;
    @Column(name = "split_config_json", nullable = false, columnDefinition = "text")
    private String splitConfigJson;
    @Column(name = "feature_config_version", nullable = false)
    private String featureConfigVersion;
    @Column(name = "source_window_start")
    private Instant sourceWindowStart;
    @Column(name = "source_window_end")
    private Instant sourceWindowEnd;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "ready_at")
    private Instant readyAt;
    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;
    @Column(name = "artifact_uri")
    private String artifactUri;
    @Column(name = "artifact_hash")
    private String artifactHash;
    @Column(name = "immutable_row_reference")
    private String immutableRowReference;
    @Column(name = "data_hash")
    private String dataHash;
    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;
    @Column(name = "lifecycle_actor_id")
    private UUID lifecycleActorId;
    @Column(name = "lifecycle_at")
    private Instant lifecycleAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDatasetId() { return datasetId; }
    public void setDatasetId(UUID datasetId) { this.datasetId = datasetId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public MlDatasetSourceType getSourceType() { return sourceType; }
    public void setSourceType(MlDatasetSourceType sourceType) { this.sourceType = sourceType; }
    public MlDatasetStatus getStatus() { return status; }
    public void setStatus(MlDatasetStatus status) { this.status = status; }
    public MlTrainingSplitStrategy getSplitStrategy() { return splitStrategy; }
    public void setSplitStrategy(MlTrainingSplitStrategy splitStrategy) { this.splitStrategy = splitStrategy; }
    public String getSplitConfigJson() { return splitConfigJson; }
    public void setSplitConfigJson(String splitConfigJson) { this.splitConfigJson = splitConfigJson; }
    public String getFeatureConfigVersion() { return featureConfigVersion; }
    public void setFeatureConfigVersion(String featureConfigVersion) { this.featureConfigVersion = featureConfigVersion; }
    public Instant getSourceWindowStart() { return sourceWindowStart; }
    public void setSourceWindowStart(Instant sourceWindowStart) { this.sourceWindowStart = sourceWindowStart; }
    public Instant getSourceWindowEnd() { return sourceWindowEnd; }
    public void setSourceWindowEnd(Instant sourceWindowEnd) { this.sourceWindowEnd = sourceWindowEnd; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant readyAt) { this.readyAt = readyAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public String getArtifactHash() { return artifactHash; }
    public void setArtifactHash(String artifactHash) { this.artifactHash = artifactHash; }
    public String getImmutableRowReference() { return immutableRowReference; }
    public void setImmutableRowReference(String immutableRowReference) { this.immutableRowReference = immutableRowReference; }
    public String getDataHash() { return dataHash; }
    public void setDataHash(String dataHash) { this.dataHash = dataHash; }
    public String getLifecycleReason() { return lifecycleReason; }
    public void setLifecycleReason(String lifecycleReason) { this.lifecycleReason = lifecycleReason; }
    public UUID getLifecycleActorId() { return lifecycleActorId; }
    public void setLifecycleActorId(UUID lifecycleActorId) { this.lifecycleActorId = lifecycleActorId; }
    public Instant getLifecycleAt() { return lifecycleAt; }
    public void setLifecycleAt(Instant lifecycleAt) { this.lifecycleAt = lifecycleAt; }
}
