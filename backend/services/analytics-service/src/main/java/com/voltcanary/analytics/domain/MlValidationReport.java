package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_validation_reports")
public class MlValidationReport {
    @Id
    private UUID id;
    @Column(name = "dataset_version_id")
    private UUID datasetVersionId;
    @Column(name = "imported_file_id")
    private UUID importedFileId;
    @Column(name = "accepted_rows", nullable = false)
    private long acceptedRows;
    @Column(name = "rejected_rows", nullable = false)
    private long rejectedRows;
    @Column(name = "warning_count", nullable = false)
    private long warningCount;
    @Column(name = "rejected_reasons_json", nullable = false, columnDefinition = "text")
    private String rejectedReasonsJson;
    @Column(name = "mapping_stats_json", nullable = false, columnDefinition = "text")
    private String mappingStatsJson;
    @Column(name = "summary", columnDefinition = "text")
    private String summary;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public UUID getImportedFileId() { return importedFileId; }
    public void setImportedFileId(UUID importedFileId) { this.importedFileId = importedFileId; }
    public long getAcceptedRows() { return acceptedRows; }
    public void setAcceptedRows(long acceptedRows) { this.acceptedRows = acceptedRows; }
    public long getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(long rejectedRows) { this.rejectedRows = rejectedRows; }
    public long getWarningCount() { return warningCount; }
    public void setWarningCount(long warningCount) { this.warningCount = warningCount; }
    public String getRejectedReasonsJson() { return rejectedReasonsJson; }
    public void setRejectedReasonsJson(String rejectedReasonsJson) { this.rejectedReasonsJson = rejectedReasonsJson; }
    public String getMappingStatsJson() { return mappingStatsJson; }
    public void setMappingStatsJson(String mappingStatsJson) { this.mappingStatsJson = mappingStatsJson; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
