package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDatasetVersionRepository extends JpaRepository<MlDatasetVersion, UUID> {
    List<MlDatasetVersion> findByAccountIdAndStatusOrderByCreatedAtDesc(UUID accountId, MlDatasetStatus status);
    List<MlDatasetVersion> findByDatasetIdOrderByVersionDesc(UUID datasetId);
    List<MlDatasetVersion> findByDatasetIdAndStatusNotOrderByVersionDesc(UUID datasetId, MlDatasetStatus status);
}
