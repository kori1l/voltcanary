package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlTrainingRunRepository extends JpaRepository<MlTrainingRun, UUID> {
    List<MlTrainingRun> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    List<MlTrainingRun> findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus status);
}
