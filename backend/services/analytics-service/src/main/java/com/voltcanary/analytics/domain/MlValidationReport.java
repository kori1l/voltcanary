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
@Table(name = "ml_validation_reports")
@Getter
@Setter
public class MlValidationReport {

    @Id
    private UUID id;

    @Column(name = "dataset_version_id")
    private UUID datasetVersionId;

    @Column(name = "imported_file_id")
    private UUID importedFileId;

    @Column(name = "accepted_rows", nullable = false)
    private long acceptedRows;

    @Column(name = "rejected_rows", nullable = false)
    private long rejectedRows;

    @Column(name = "warning_count", nullable = false)
    private long warningCount;

    @Column(name = "rejected_reasons_json", nullable = false, columnDefinition = "text")
    private String rejectedReasonsJson;

    @Column(name = "mapping_stats_json", nullable = false, columnDefinition = "text")
    private String mappingStatsJson;

    @Column(name = "quality_profile_json", nullable = false, columnDefinition = "text")
    private String qualityProfileJson;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
