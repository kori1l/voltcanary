package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MlDatasetVersionRepository extends JpaRepository<MlDatasetVersion, UUID> {
    @Query(
        """
        select v from MlDatasetVersion v, MlDataset d
        where v.accountId = :accountId and d.accountId = :accountId
          and d.id = v.datasetId and v.status in :statuses
          and (:latestOnly = false or v.version = (select max(candidate.version) from MlDatasetVersion candidate
              where candidate.accountId = :accountId and candidate.datasetId = v.datasetId
                and candidate.status in :statuses))
        order by d.createdAt desc, d.id desc, v.version desc, v.id desc
        """
    )
    Slice<MlDatasetVersion> findInventory(
        UUID accountId,
        List<MlDatasetStatus> statuses,
        boolean latestOnly,
        Pageable pageable
    );

    List<MlDatasetVersion> findByDatasetIdOrderByVersionDesc(UUID datasetId);
    List<MlDatasetVersion> findByDatasetIdAndStatusNotOrderByVersionDesc(
        UUID datasetId,
        MlDatasetStatus status
    );
}
