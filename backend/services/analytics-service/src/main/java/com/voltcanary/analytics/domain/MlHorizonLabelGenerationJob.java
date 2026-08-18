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
@Table(name = "ml_horizon_label_generation_jobs")
public class MlHorizonLabelGenerationJob {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Column(name = "submitted_by")
    private UUID submittedBy;
    @Column(name = "horizon_days", nullable = false)
    private int horizonDays;
    @Column(name = "generation_version", nullable = false)
    private String generationVersion;
    @Column(name = "include_preventive_maintenance", nullable = false)
    private boolean includePreventiveMaintenance;
    @Column(name = "input_lineage_hash", nullable = false)
    private String inputLineageHash;
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlHorizonLabelGenerationStatus status;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "progress_percent")
    private Integer progressPercent;
    @Column(name = "message", columnDefinition = "text")
    private String message;
    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;
    @Column(name = "lineage_hash")
    private String lineageHash;
    @Column(name = "generated_labels")
    private Integer generatedLabels;
    @Column(name = "positive_labels")
    private Integer positiveLabels;
    @Column(name = "normal_labels")
    private Integer normalLabels;
    @Column(name = "maintenance_excluded_labels")
    private Integer maintenanceExcludedLabels;
    @Column(name = "unresolved_labels")
    private Integer unresolvedLabels;
    @Column(name = "unobservable_rows")
    private Integer unobservableRows;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public UUID getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(UUID submittedBy) { this.submittedBy = submittedBy; }
    public int getHorizonDays() { return horizonDays; }
    public void setHorizonDays(int horizonDays) { this.horizonDays = horizonDays; }
    public String getGenerationVersion() { return generationVersion; }
    public void setGenerationVersion(String generationVersion) { this.generationVersion = generationVersion; }
    public boolean isIncludePreventiveMaintenance() { return includePreventiveMaintenance; }
    public void setIncludePreventiveMaintenance(boolean includePreventiveMaintenance) { this.includePreventiveMaintenance = includePreventiveMaintenance; }
    public String getInputLineageHash() { return inputLineageHash; }
    public void setInputLineageHash(String inputLineageHash) { this.inputLineageHash = inputLineageHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public MlHorizonLabelGenerationStatus getStatus() { return status; }
    public void setStatus(MlHorizonLabelGenerationStatus status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getLineageHash() { return lineageHash; }
    public void setLineageHash(String lineageHash) { this.lineageHash = lineageHash; }
    public Integer getGeneratedLabels() { return generatedLabels; }
    public void setGeneratedLabels(Integer generatedLabels) { this.generatedLabels = generatedLabels; }
    public Integer getPositiveLabels() { return positiveLabels; }
    public void setPositiveLabels(Integer positiveLabels) { this.positiveLabels = positiveLabels; }
    public Integer getNormalLabels() { return normalLabels; }
    public void setNormalLabels(Integer normalLabels) { this.normalLabels = normalLabels; }
    public Integer getMaintenanceExcludedLabels() { return maintenanceExcludedLabels; }
    public void setMaintenanceExcludedLabels(Integer maintenanceExcludedLabels) { this.maintenanceExcludedLabels = maintenanceExcludedLabels; }
    public Integer getUnresolvedLabels() { return unresolvedLabels; }
    public void setUnresolvedLabels(Integer unresolvedLabels) { this.unresolvedLabels = unresolvedLabels; }
    public Integer getUnobservableRows() { return unobservableRows; }
    public void setUnobservableRows(Integer unobservableRows) { this.unobservableRows = unobservableRows; }
}
