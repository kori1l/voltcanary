package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlLabelRepository extends JpaRepository<MlLabel, UUID> {
    List<MlLabel> findByAccountIdAndDatasetVersionId(UUID accountId, UUID datasetVersionId);
}
