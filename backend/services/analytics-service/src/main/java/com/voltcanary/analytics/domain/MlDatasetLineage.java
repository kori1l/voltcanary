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
@Table(name = "ml_dataset_lineage")
public class MlDatasetLineage {
    @Id
    private UUID id;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;
    @Column(name = "source_reference", nullable = false)
    private String sourceReference;
    @Column(name = "filter_json", nullable = false, columnDefinition = "text")
    private String filterJson;
    @Column(name = "selected_devices_json", nullable = false, columnDefinition = "text")
    private String selectedDevicesJson;
    @Column(name = "selected_metrics_json", nullable = false, columnDefinition = "text")
    private String selectedMetricsJson;
    @Column(name = "source_window_start")
    private Instant sourceWindowStart;
    @Column(name = "source_window_end")
    private Instant sourceWindowEnd;
    @Column(name = "content_hash")
    private String contentHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public MlDatasetSourceType getSourceType() { return sourceType; }
    public void setSourceType(MlDatasetSourceType sourceType) { this.sourceType = sourceType; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String filterJson) { this.filterJson = filterJson; }
    public String getSelectedDevicesJson() { return selectedDevicesJson; }
    public void setSelectedDevicesJson(String selectedDevicesJson) { this.selectedDevicesJson = selectedDevicesJson; }
    public String getSelectedMetricsJson() { return selectedMetricsJson; }
    public void setSelectedMetricsJson(String selectedMetricsJson) { this.selectedMetricsJson = selectedMetricsJson; }
    public Instant getSourceWindowStart() { return sourceWindowStart; }
    public void setSourceWindowStart(Instant sourceWindowStart) { this.sourceWindowStart = sourceWindowStart; }
    public Instant getSourceWindowEnd() { return sourceWindowEnd; }
    public void setSourceWindowEnd(Instant sourceWindowEnd) { this.sourceWindowEnd = sourceWindowEnd; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
