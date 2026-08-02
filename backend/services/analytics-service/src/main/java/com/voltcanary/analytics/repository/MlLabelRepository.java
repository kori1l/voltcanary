package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MlLabelRepository extends JpaRepository<MlLabel, UUID> {
    List<MlLabel> findByAccountIdAndDatasetVersionId(UUID accountId, UUID datasetVersionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from MlLabel label
            where label.accountId = :accountId
              and label.datasetVersionId = :datasetVersionId
              and label.source = com.voltcanary.analytics.domain.MlLabelSource.MAINTENANCE_OUTCOME
              and label.horizonDays = :horizonDays
            """)
    int deleteGeneratedOutcomeLabels(
            @Param("accountId") UUID accountId,
            @Param("datasetVersionId") UUID datasetVersionId,
            @Param("horizonDays") int horizonDays);
}
