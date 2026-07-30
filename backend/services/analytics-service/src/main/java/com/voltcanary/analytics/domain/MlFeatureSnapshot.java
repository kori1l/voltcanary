package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_feature_snapshots")
public class MlFeatureSnapshot {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Column(name = "feature_set_version", nullable = false)
    private String featureSetVersion;
    @Column(name = "code_config_version", nullable = false)
    private String codeConfigVersion;
    @Column(name = "feature_config_json", nullable = false, columnDefinition = "text")
    private String featureConfigJson;
    @Column(name = "feature_schema_hash", nullable = false)
    private String featureSchemaHash;
    @Column(name = "row_count", nullable = false)
    private long rowCount;
    @Column(name = "rejected_row_count", nullable = false)
    private long rejectedRowCount;
    @Column(name = "artifact_uri")
    private String artifactUri;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public String getFeatureSetVersion() { return featureSetVersion; }
    public void setFeatureSetVersion(String featureSetVersion) { this.featureSetVersion = featureSetVersion; }
    public String getCodeConfigVersion() { return codeConfigVersion; }
    public void setCodeConfigVersion(String codeConfigVersion) { this.codeConfigVersion = codeConfigVersion; }
    public String getFeatureConfigJson() { return featureConfigJson; }
    public void setFeatureConfigJson(String featureConfigJson) { this.featureConfigJson = featureConfigJson; }
    public String getFeatureSchemaHash() { return featureSchemaHash; }
    public void setFeatureSchemaHash(String featureSchemaHash) { this.featureSchemaHash = featureSchemaHash; }
    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
    public long getRejectedRowCount() { return rejectedRowCount; }
    public void setRejectedRowCount(long rejectedRowCount) { this.rejectedRowCount = rejectedRowCount; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
