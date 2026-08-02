package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlFeatureRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlFeatureRowRepository extends JpaRepository<MlFeatureRow, UUID> {
    List<MlFeatureRow> findByFeatureSnapshotIdOrderByPredictionAtAsc(UUID featureSnapshotId);
}
