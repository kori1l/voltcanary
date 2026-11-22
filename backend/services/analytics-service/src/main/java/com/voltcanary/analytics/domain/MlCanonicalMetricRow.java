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
@Table(name = "ml_canonical_metric_rows")
@Getter
@Setter
public class MlCanonicalMetricRow {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "external_device_key")
    private String externalDeviceKey;

    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "sensor_id")
    private String sensorId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "canonical_metric_name", nullable = false)
    private String canonicalMetricName;

    @Column(name = "numeric_value", nullable = false)
    private double numericValue;

    @Column(name = "raw_unit")
    private String rawUnit;

    @Column(name = "canonical_unit")
    private String canonicalUnit;

    @Column(name = "quality_flags", nullable = false, columnDefinition = "text")
    private String qualityFlags;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;

    @Column(name = "source_lineage", nullable = false, columnDefinition = "text")
    private String sourceLineage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
