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
@Table(name = "ml_canonical_metric_rows")
public class MlCanonicalMetricRow {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;
    @Column(name = "device_id")
    private UUID deviceId;
    @Column(name = "external_device_key")
    private String externalDeviceKey;
    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "source_id")
    private String sourceId;
    @Column(name = "sensor_id")
    private String sensorId;
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
    @Column(name = "metric_name", nullable = false)
    private String metricName;
    @Column(name = "canonical_metric_name", nullable = false)
    private String canonicalMetricName;
    @Column(name = "numeric_value", nullable = false)
    private double numericValue;
    @Column(name = "raw_unit")
    private String rawUnit;
    @Column(name = "canonical_unit")
    private String canonicalUnit;
    @Column(name = "quality_flags", nullable = false, columnDefinition = "text")
    private String qualityFlags;
    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;
    @Column(name = "source_lineage", nullable = false, columnDefinition = "text")
    private String sourceLineage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public MlDatasetSourceType getSourceType() { return sourceType; }
    public void setSourceType(MlDatasetSourceType sourceType) { this.sourceType = sourceType; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getExternalDeviceKey() { return externalDeviceKey; }
    public void setExternalDeviceKey(String externalDeviceKey) { this.externalDeviceKey = externalDeviceKey; }
    public String getVirtualDeviceKey() { return virtualDeviceKey; }
    public void setVirtualDeviceKey(String virtualDeviceKey) { this.virtualDeviceKey = virtualDeviceKey; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getCanonicalMetricName() { return canonicalMetricName; }
    public void setCanonicalMetricName(String canonicalMetricName) { this.canonicalMetricName = canonicalMetricName; }
    public double getNumericValue() { return numericValue; }
    public void setNumericValue(double numericValue) { this.numericValue = numericValue; }
    public String getRawUnit() { return rawUnit; }
    public void setRawUnit(String rawUnit) { this.rawUnit = rawUnit; }
    public String getCanonicalUnit() { return canonicalUnit; }
    public void setCanonicalUnit(String canonicalUnit) { this.canonicalUnit = canonicalUnit; }
    public String getQualityFlags() { return qualityFlags; }
    public void setQualityFlags(String qualityFlags) { this.qualityFlags = qualityFlags; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getSourceLineage() { return sourceLineage; }
    public void setSourceLineage(String sourceLineage) { this.sourceLineage = sourceLineage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
