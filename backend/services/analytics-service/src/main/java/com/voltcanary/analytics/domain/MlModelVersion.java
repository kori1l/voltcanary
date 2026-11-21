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
@Table(name = "ml_model_versions")
@Getter
@Setter
public class MlModelVersion {
    @Id
    private UUID id;
    @Column(name = "account_id")
    private UUID accountId;
    @Column(name = "model_key", nullable = false)
    private String modelKey;
    @Column(name = "version", nullable = false)
    private int version;
    @Enumerated(EnumType.STRING)
    @Column(name = "family", nullable = false)
    private MlModelFamily family;
    @Column(name = "algorithm", nullable = false)
    private String algorithm;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlModelStatus status;
    @Column(name = "training_run_id")
    private UUID trainingRunId;
    @Column(name = "dataset_version_id")
    private UUID datasetVersionId;
    @Column(name = "feature_snapshot_id")
    private UUID featureSnapshotId;
    @Column(name = "feature_schema_hash")
    private String featureSchemaHash;
    @Column(name = "artifact_uri")
    private String artifactUri;
    @Column(name = "artifact_manifest_uri")
    private String artifactManifestUri;
    @Column(name = "artifact_hash")
    private String artifactHash;
    @Column(name = "artifact_size_bytes")
    private Long artifactSizeBytes;
    @Column(name = "compatible_device_types_json", nullable = false, columnDefinition = "text")
    private String compatibleDeviceTypesJson;
    @Column(name = "supported_metrics_json", nullable = false, columnDefinition = "text")
    private String supportedMetricsJson;
    @Column(name = "evaluation_metrics_json", nullable = false, columnDefinition = "text")
    private String evaluationMetricsJson;
    @Column(name = "approved_by")
    private UUID approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;
    @Column(name = "lifecycle_actor_id")
    private UUID lifecycleActorId;
    @Column(name = "lifecycle_at")
    private Instant lifecycleAt;

}
