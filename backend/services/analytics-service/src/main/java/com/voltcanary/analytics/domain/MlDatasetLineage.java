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
@Table(name = "ml_dataset_lineage")
@Getter
@Setter
public class MlDatasetLineage {

    @Id
    private UUID id;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;

    @Column(name = "source_reference", nullable = false)
    private String sourceReference;

    @Column(name = "filter_json", nullable = false, columnDefinition = "text")
    private String filterJson;

    @Column(name = "selected_devices_json", nullable = false, columnDefinition = "text")
    private String selectedDevicesJson;

    @Column(name = "selected_metrics_json", nullable = false, columnDefinition = "text")
    private String selectedMetricsJson;

    @Column(name = "source_window_start")
    private Instant sourceWindowStart;

    @Column(name = "source_window_end")
    private Instant sourceWindowEnd;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
