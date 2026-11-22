package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MlDatasetVersionRepository extends JpaRepository<MlDatasetVersion, UUID> {
    @Query("""
            select v from MlDatasetVersion v
            where v.accountId = :accountId and v.status not in :excluded
              and v.version = (select max(candidate.version) from MlDatasetVersion candidate
                  where candidate.accountId = :accountId and candidate.datasetId = v.datasetId
                    and candidate.status not in :excluded)
            order by v.version desc
            """)
    List<MlDatasetVersion> findLatestVisibleByAccountId(UUID accountId, List<MlDatasetStatus> excluded);
    List<MlDatasetVersion> findByAccountIdOrderByVersionDesc(UUID accountId);
    List<MlDatasetVersion> findByAccountIdAndStatusOrderByCreatedAtDesc(UUID accountId, MlDatasetStatus status);
    List<MlDatasetVersion> findByDatasetIdOrderByVersionDesc(UUID datasetId);
    List<MlDatasetVersion> findByDatasetIdAndStatusNotOrderByVersionDesc(UUID datasetId, MlDatasetStatus status);
}
