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
@Table(name = "ml_feature_rows")
@Getter
@Setter
public class MlFeatureRow {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "feature_snapshot_id", nullable = false)
    private UUID featureSnapshotId;
    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @Column(name = "device_id")
    private UUID deviceId;
    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "prediction_at", nullable = false)
    private Instant predictionAt;
    @Column(name = "horizon_days")
    private Integer horizonDays;
    @Column(name = "feature_values_json", nullable = false, columnDefinition = "text")
    private String featureValuesJson;
    @Column(name = "label_values_json", columnDefinition = "text")
    private String labelValuesJson;
    @Column(name = "split_assignment")
    private String splitAssignment;
    @Column(name = "source_lineage_json", nullable = false, columnDefinition = "text")
    private String sourceLineageJson;
    @Column(name = "quality_flags_json", nullable = false, columnDefinition = "text")
    private String qualityFlagsJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
