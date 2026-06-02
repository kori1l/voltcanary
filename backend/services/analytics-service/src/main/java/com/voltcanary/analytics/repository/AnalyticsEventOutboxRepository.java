package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.AnalyticsEventOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventOutboxRepository extends JpaRepository<AnalyticsEventOutbox, UUID> {

    Optional<AnalyticsEventOutbox> findByEventKey(String eventKey);

    List<AnalyticsEventOutbox> findTop100ByStatusOrderByCreatedAtAsc(String status);

    long deleteByStatusAndPublishedAtBefore(String status, Instant cutoff);
}
