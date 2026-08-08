package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlModelVersionRepository extends JpaRepository<MlModelVersion, UUID> {
    List<MlModelVersion> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    List<MlModelVersion> findByAccountIdAndStatusOrderByCreatedAtDesc(UUID accountId, MlModelStatus status);
    List<MlModelVersion> findByModelKeyOrderByVersionDesc(String modelKey);
    long countByTrainingRunId(UUID trainingRunId);
    long countByDatasetVersionId(UUID datasetVersionId);
    long countByFeatureSnapshotId(UUID featureSnapshotId);
}
