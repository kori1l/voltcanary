package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlImportedFile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlImportedFileRepository extends JpaRepository<MlImportedFile, UUID> {
    List<MlImportedFile> findByAccountIdOrderByImportedAtDesc(UUID accountId);
    List<MlImportedFile> findByDatasetIdOrderByImportedAtDesc(UUID datasetId);
}
