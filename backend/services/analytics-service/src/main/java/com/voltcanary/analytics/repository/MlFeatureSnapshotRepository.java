package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlFeatureSnapshotRepository extends JpaRepository<MlFeatureSnapshot, UUID> {
    List<MlFeatureSnapshot> findByDatasetVersionIdOrderByCreatedAtDesc(UUID datasetVersionId);
    long countByDatasetVersionId(UUID datasetVersionId);
    Optional<MlFeatureSnapshot> findFirstByDatasetVersionIdAndFeatureSchemaHashOrderByCreatedAtDesc(UUID datasetVersionId, String featureSchemaHash);
}
