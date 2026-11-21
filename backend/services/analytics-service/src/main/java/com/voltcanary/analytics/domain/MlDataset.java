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
@Table(name = "ml_datasets")
@Getter
@Setter
public class MlDataset {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "description", columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MlDatasetSourceType sourceType;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
