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
@Table(name = "ml_imported_files")
@Getter
@Setter
public class MlImportedFile {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;
    @Column(name = "original_filename", nullable = false)
    private String originalFilename;
    @Column(name = "content_hash", nullable = false)
    private String contentHash;
    @Column(name = "content_type", nullable = false)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "mapping_version", nullable = false)
    private String mappingVersion;
    @Column(name = "mapping_json", nullable = false, columnDefinition = "text")
    private String mappingJson;
    @Column(name = "artifact_uri")
    private String artifactUri;
    @Column(name = "uploaded_by")
    private UUID uploadedBy;
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

}
