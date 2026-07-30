package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlImportedMetricRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlImportedMetricRowRepository extends JpaRepository<MlImportedMetricRow, UUID> {
    List<MlImportedMetricRow> findByImportedFileIdOrderBySourceRowNumber(UUID importedFileId);
}
