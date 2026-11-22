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
@Table(name = "ml_dataset_versions")
@Getter
@Setter
public class MlDatasetVersion {

    @jakarta.persistence.Version
    @Column(name = "row_revision", nullable = false)
    private Long rowRevision;

    @Id
    private UUID id;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlDatasetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_strategy", nullable = false)
    private MlTrainingSplitStrategy splitStrategy;

    @Column(name = "split_config_json", nullable = false, columnDefinition = "text")
    private String splitConfigJson;

    @Column(name = "feature_config_version", nullable = false)
    private String featureConfigVersion;

    @Column(name = "source_window_start")
    private Instant sourceWindowStart;

    @Column(name = "source_window_end")
    private Instant sourceWindowEnd;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "artifact_uri")
    private String artifactUri;

    @Column(name = "artifact_hash")
    private String artifactHash;

    @Column(name = "immutable_row_reference")
    private String immutableRowReference;

    @Column(name = "data_hash")
    private String dataHash;

    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;

    @Column(name = "lifecycle_actor_id")
    private UUID lifecycleActorId;

    @Column(name = "lifecycle_at")
    private Instant lifecycleAt;
}
