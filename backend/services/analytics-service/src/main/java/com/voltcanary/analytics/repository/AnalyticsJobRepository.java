package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.AnalyticsJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsJobRepository extends JpaRepository<AnalyticsJob, UUID> {

    List<AnalyticsJob> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<AnalyticsJob> findFirstByDeviceIdAndStatusInOrderByCreatedAtDesc(UUID deviceId, List<String> statuses);
}
