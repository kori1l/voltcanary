package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlValidationReport;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlValidationReportRepository extends JpaRepository<MlValidationReport, UUID> {
    List<MlValidationReport> findByDatasetVersionIdOrderByCreatedAtDesc(UUID datasetVersionId);
    List<MlValidationReport> findByImportedFileIdOrderByCreatedAtDesc(UUID importedFileId);
}
