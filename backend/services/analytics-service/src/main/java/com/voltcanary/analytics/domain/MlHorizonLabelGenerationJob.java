package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ml_horizon_label_generation_jobs")
@Getter
@Setter
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

}
