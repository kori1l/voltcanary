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
@Table(name = "ml_imported_metric_rows")
public class MlImportedMetricRow {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "imported_file_id", nullable = false)
    private UUID importedFileId;
    @Column(name = "external_device_key", nullable = false)
    private String externalDeviceKey;
    @Column(name = "matched_device_id")
    private UUID matchedDeviceId;
    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
    @Column(name = "metric_name", nullable = false)
    private String metricName;
    @Column(name = "numeric_value", nullable = false)
    private double numericValue;
    @Column(name = "unit")
    private String unit;
    @Enumerated(EnumType.STRING)
    @Column(name = "label")
    private MlLabelType label;
    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;
    @Column(name = "source_row_number", nullable = false)
    private long sourceRowNumber;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getImportedFileId() { return importedFileId; }
    public void setImportedFileId(UUID importedFileId) { this.importedFileId = importedFileId; }
    public String getExternalDeviceKey() { return externalDeviceKey; }
    public void setExternalDeviceKey(String externalDeviceKey) { this.externalDeviceKey = externalDeviceKey; }
    public UUID getMatchedDeviceId() { return matchedDeviceId; }
    public void setMatchedDeviceId(UUID matchedDeviceId) { this.matchedDeviceId = matchedDeviceId; }
    public String getVirtualDeviceKey() { return virtualDeviceKey; }
    public void setVirtualDeviceKey(String virtualDeviceKey) { this.virtualDeviceKey = virtualDeviceKey; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public double getNumericValue() { return numericValue; }
    public void setNumericValue(double numericValue) { this.numericValue = numericValue; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public MlLabelType getLabel() { return label; }
    public void setLabel(MlLabelType label) { this.label = label; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public long getSourceRowNumber() { return sourceRowNumber; }
    public void setSourceRowNumber(long sourceRowNumber) { this.sourceRowNumber = sourceRowNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
