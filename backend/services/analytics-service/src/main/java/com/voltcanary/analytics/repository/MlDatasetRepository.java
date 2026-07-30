package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDataset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDatasetRepository extends JpaRepository<MlDataset, UUID> {
    List<MlDataset> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
