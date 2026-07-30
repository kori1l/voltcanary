package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDatasetLineage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDatasetLineageRepository extends JpaRepository<MlDatasetLineage, UUID> {
    List<MlDatasetLineage> findByDatasetVersionId(UUID datasetVersionId);
}
