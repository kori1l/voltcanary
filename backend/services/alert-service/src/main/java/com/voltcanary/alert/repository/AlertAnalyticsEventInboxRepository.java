package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.AlertAnalyticsEventInboxEntry;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertAnalyticsEventInboxRepository extends JpaRepository<AlertAnalyticsEventInboxEntry, UUID> {

    Optional<AlertAnalyticsEventInboxEntry> findBySourceNameAndEventId(String sourceName, UUID eventId);

    @Query("""
            select entry
            from AlertAnalyticsEventInboxEntry entry
            where (entry.status in :retryableStatuses and entry.nextAttemptAt <= :now)
               or (entry.status = :processingStatus and entry.processingStartedAt <= :staleBefore)
            order by entry.updatedAt
            """)
    List<AlertAnalyticsEventInboxEntry> findDue(
            @Param("retryableStatuses") Collection<AlertAnalyticsEventInboxStatus> retryableStatuses,
            @Param("processingStatus") AlertAnalyticsEventInboxStatus processingStatus,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable);
}
