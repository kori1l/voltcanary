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
@Table(name = "ml_labels")
@Getter
@Setter
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
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "deleted_by")
    private UUID deletedBy;
    @Column(name = "deletion_reason", columnDefinition = "text")
    private String deletionReason;

}
