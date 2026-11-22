package com.voltcanary.analytics.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.voltcanary.analytics.domain.AnalyticsJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsJobRepository extends JpaRepository<AnalyticsJob, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalyticsJob job where job.id=:id")
    Optional<AnalyticsJob> lockById(@Param("id") UUID id);


    List<AnalyticsJob> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<AnalyticsJob> findFirstByDeviceIdAndStatusInOrderByCreatedAtDesc(UUID deviceId, List<String> statuses);
}
