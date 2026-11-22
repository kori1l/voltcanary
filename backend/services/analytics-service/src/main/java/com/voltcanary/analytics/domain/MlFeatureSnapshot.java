package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ml_feature_snapshots")
@Getter
@Setter
public class MlFeatureSnapshot {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Column(name = "feature_set_version", nullable = false)
    private String featureSetVersion;

    @Column(name = "code_config_version", nullable = false)
    private String codeConfigVersion;

    @Column(name = "feature_config_json", nullable = false, columnDefinition = "text")
    private String featureConfigJson;

    @Column(name = "feature_schema_hash", nullable = false)
    private String featureSchemaHash;

    @Column(name = "feature_data_hash")
    private String featureDataHash;

    @Column(name = "feature_config_hash")
    private String featureConfigHash;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Column(name = "rejected_row_count", nullable = false)
    private long rejectedRowCount;

    @Column(name = "artifact_uri")
    private String artifactUri;

    @Column(name = "immutable_row_reference")
    private String immutableRowReference;

    @Column(name = "model_family")
    private String modelFamily;

    @Column(name = "horizon_days")
    private Integer horizonDays;

    @Column(name = "feature_template_id")
    private String featureTemplateId;

    @Column(name = "feature_template_version")
    private String featureTemplateVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus = "ACTIVE";

    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;

    @Column(name = "lifecycle_actor_id")
    private UUID lifecycleActorId;

    @Column(name = "lifecycle_at")
    private Instant lifecycleAt;
}
