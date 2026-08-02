package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_feature_rows")
public class MlFeatureRow {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "feature_snapshot_id", nullable = false)
    private UUID featureSnapshotId;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @Column(name = "device_id")
    private UUID deviceId;
    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "prediction_at", nullable = false)
    private Instant predictionAt;
    @Column(name = "horizon_days")
    private Integer horizonDays;
    @Column(name = "feature_values_json", nullable = false, columnDefinition = "text")
    private String featureValuesJson;
    @Column(name = "label_values_json", columnDefinition = "text")
    private String labelValuesJson;
    @Column(name = "split_assignment")
    private String splitAssignment;
    @Column(name = "source_lineage_json", nullable = false, columnDefinition = "text")
    private String sourceLineageJson;
    @Column(name = "quality_flags_json", nullable = false, columnDefinition = "text")
    private String qualityFlagsJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getFeatureSnapshotId() { return featureSnapshotId; }
    public void setFeatureSnapshotId(UUID featureSnapshotId) { this.featureSnapshotId = featureSnapshotId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getVirtualDeviceKey() { return virtualDeviceKey; }
    public void setVirtualDeviceKey(String virtualDeviceKey) { this.virtualDeviceKey = virtualDeviceKey; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public Instant getPredictionAt() { return predictionAt; }
    public void setPredictionAt(Instant predictionAt) { this.predictionAt = predictionAt; }
    public Integer getHorizonDays() { return horizonDays; }
    public void setHorizonDays(Integer horizonDays) { this.horizonDays = horizonDays; }
    public String getFeatureValuesJson() { return featureValuesJson; }
    public void setFeatureValuesJson(String featureValuesJson) { this.featureValuesJson = featureValuesJson; }
    public String getLabelValuesJson() { return labelValuesJson; }
    public void setLabelValuesJson(String labelValuesJson) { this.labelValuesJson = labelValuesJson; }
    public String getSplitAssignment() { return splitAssignment; }
    public void setSplitAssignment(String splitAssignment) { this.splitAssignment = splitAssignment; }
    public String getSourceLineageJson() { return sourceLineageJson; }
    public void setSourceLineageJson(String sourceLineageJson) { this.sourceLineageJson = sourceLineageJson; }
    public String getQualityFlagsJson() { return qualityFlagsJson; }
    public void setQualityFlagsJson(String qualityFlagsJson) { this.qualityFlagsJson = qualityFlagsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
