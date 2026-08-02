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
@Table(name = "ml_labels")
public class MlLabel {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "dataset_version_id")
    private UUID datasetVersionId;
    @Column(name = "device_id")
    private UUID deviceId;
    @Column(name = "external_device_key")
    private String externalDeviceKey;
    @Column(name = "metric_name")
    private String metricName;
    @Column(name = "label_start_at", nullable = false)
    private Instant labelStartAt;
    @Column(name = "label_end_at", nullable = false)
    private Instant labelEndAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false)
    private MlLabelType label;
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private MlLabelSource source;
    @Column(name = "reason", columnDefinition = "text")
    private String reason;
    @Column(name = "horizon_days")
    private Integer horizonDays;
    @Column(name = "generation_version")
    private String generationVersion;
    @Column(name = "source_outcome_ids_json", columnDefinition = "text")
    private String sourceOutcomeIdsJson;
    @Column(name = "lineage_hash")
    private String lineageHash;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getExternalDeviceKey() { return externalDeviceKey; }
    public void setExternalDeviceKey(String externalDeviceKey) { this.externalDeviceKey = externalDeviceKey; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public Instant getLabelStartAt() { return labelStartAt; }
    public void setLabelStartAt(Instant labelStartAt) { this.labelStartAt = labelStartAt; }
    public Instant getLabelEndAt() { return labelEndAt; }
    public void setLabelEndAt(Instant labelEndAt) { this.labelEndAt = labelEndAt; }
    public MlLabelType getLabel() { return label; }
    public void setLabel(MlLabelType label) { this.label = label; }
    public MlLabelSource getSource() { return source; }
    public void setSource(MlLabelSource source) { this.source = source; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getHorizonDays() { return horizonDays; }
    public void setHorizonDays(Integer horizonDays) { this.horizonDays = horizonDays; }
    public String getGenerationVersion() { return generationVersion; }
    public void setGenerationVersion(String generationVersion) { this.generationVersion = generationVersion; }
    public String getSourceOutcomeIdsJson() { return sourceOutcomeIdsJson; }
    public void setSourceOutcomeIdsJson(String sourceOutcomeIdsJson) { this.sourceOutcomeIdsJson = sourceOutcomeIdsJson; }
    public String getLineageHash() { return lineageHash; }
    public void setLineageHash(String lineageHash) { this.lineageHash = lineageHash; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
