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
@Table(name = "ml_imported_metric_rows")
@Getter
@Setter
public class MlImportedMetricRow {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "imported_file_id", nullable = false)
    private UUID importedFileId;

    @Column(name = "external_device_key", nullable = false)
    private String externalDeviceKey;

    @Column(name = "matched_device_id")
    private UUID matchedDeviceId;

    @Column(name = "virtual_device_key")
    private String virtualDeviceKey;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "numeric_value", nullable = false)
    private double numericValue;

    @Column(name = "unit")
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "label")
    private MlLabelType label;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;

    @Column(name = "source_row_number", nullable = false)
    private long sourceRowNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
