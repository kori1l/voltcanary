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
@Table(name = "ml_model_versions")
public class MlModelVersion {
    @Id
    private UUID id;
    @Column(name = "account_id")
    private UUID accountId;
    @Column(name = "model_key", nullable = false)
    private String modelKey;
    @Column(name = "version", nullable = false)
    private int version;
    @Enumerated(EnumType.STRING)
    @Column(name = "family", nullable = false)
    private MlModelFamily family;
    @Column(name = "algorithm", nullable = false)
    private String algorithm;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlModelStatus status;
    @Column(name = "training_run_id")
    private UUID trainingRunId;
    @Column(name = "dataset_version_id")
    private UUID datasetVersionId;
    @Column(name = "feature_snapshot_id")
    private UUID featureSnapshotId;
    @Column(name = "feature_schema_hash")
    private String featureSchemaHash;
    @Column(name = "artifact_uri")
    private String artifactUri;
    @Column(name = "artifact_manifest_uri")
    private String artifactManifestUri;
    @Column(name = "artifact_hash")
    private String artifactHash;
    @Column(name = "artifact_size_bytes")
    private Long artifactSizeBytes;
    @Column(name = "compatible_device_types_json", nullable = false, columnDefinition = "text")
    private String compatibleDeviceTypesJson;
    @Column(name = "supported_metrics_json", nullable = false, columnDefinition = "text")
    private String supportedMetricsJson;
    @Column(name = "evaluation_metrics_json", nullable = false, columnDefinition = "text")
    private String evaluationMetricsJson;
    @Column(name = "approved_by")
    private UUID approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getModelKey() { return modelKey; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public MlModelFamily getFamily() { return family; }
    public void setFamily(MlModelFamily family) { this.family = family; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public MlModelStatus getStatus() { return status; }
    public void setStatus(MlModelStatus status) { this.status = status; }
    public UUID getTrainingRunId() { return trainingRunId; }
    public void setTrainingRunId(UUID trainingRunId) { this.trainingRunId = trainingRunId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public UUID getFeatureSnapshotId() { return featureSnapshotId; }
    public void setFeatureSnapshotId(UUID featureSnapshotId) { this.featureSnapshotId = featureSnapshotId; }
    public String getFeatureSchemaHash() { return featureSchemaHash; }
    public void setFeatureSchemaHash(String featureSchemaHash) { this.featureSchemaHash = featureSchemaHash; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public String getArtifactManifestUri() { return artifactManifestUri; }
    public void setArtifactManifestUri(String artifactManifestUri) { this.artifactManifestUri = artifactManifestUri; }
    public String getArtifactHash() { return artifactHash; }
    public void setArtifactHash(String artifactHash) { this.artifactHash = artifactHash; }
    public Long getArtifactSizeBytes() { return artifactSizeBytes; }
    public void setArtifactSizeBytes(Long artifactSizeBytes) { this.artifactSizeBytes = artifactSizeBytes; }
    public String getCompatibleDeviceTypesJson() { return compatibleDeviceTypesJson; }
    public void setCompatibleDeviceTypesJson(String compatibleDeviceTypesJson) { this.compatibleDeviceTypesJson = compatibleDeviceTypesJson; }
    public String getSupportedMetricsJson() { return supportedMetricsJson; }
    public void setSupportedMetricsJson(String supportedMetricsJson) { this.supportedMetricsJson = supportedMetricsJson; }
    public String getEvaluationMetricsJson() { return evaluationMetricsJson; }
    public void setEvaluationMetricsJson(String evaluationMetricsJson) { this.evaluationMetricsJson = evaluationMetricsJson; }
    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
