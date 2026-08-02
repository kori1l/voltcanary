package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlCanonicalMetricRowRepository extends JpaRepository<MlCanonicalMetricRow, UUID> {
    List<MlCanonicalMetricRow> findByDatasetVersionIdOrderByTimestampAsc(UUID datasetVersionId);
}
