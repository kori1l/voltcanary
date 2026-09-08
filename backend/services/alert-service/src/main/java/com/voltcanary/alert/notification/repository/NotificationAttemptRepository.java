package com.voltcanary.alert.notification.repository;

import com.voltcanary.alert.notification.domain.NotificationAttempt;
import com.voltcanary.alert.notification.domain.NotificationAttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByAlertIdOrderByCreatedAtDesc(UUID alertId);

    Optional<NotificationAttempt> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select attempt
            from NotificationAttempt attempt
            where (
                    attempt.status in :readyStatuses
                    and attempt.nextAttemptAt <= :now
                    and (attempt.lockedAt is null or attempt.lockedAt <= :lockExpiredBefore)
                  )
               or (
                    attempt.status = :processingStatus
                    and attempt.lockedAt <= :lockExpiredBefore
                  )
            order by attempt.createdAt asc
            """)
    List<NotificationAttempt> findClaimCandidates(
            @Param("readyStatuses") List<NotificationAttemptStatus> readyStatuses,
            @Param("processingStatus") NotificationAttemptStatus processingStatus,
            @Param("now") Instant now,
            @Param("lockExpiredBefore") Instant lockExpiredBefore,
            Pageable pageable);

    @Modifying
    @Query("""
            update NotificationAttempt attempt
            set attempt.status = :processingStatus,
                attempt.lockedAt = :lockedAt,
                attempt.lockedBy = :lockedBy,
                attempt.updatedAt = :lockedAt
            where attempt.id = :attemptId
              and (
                    (
                        attempt.status in :readyStatuses
                        and attempt.nextAttemptAt <= :lockedAt
                        and (attempt.lockedAt is null or attempt.lockedAt <= :lockExpiredBefore)
                    )
                    or (
                        attempt.status = :processingStatus
                        and attempt.lockedAt <= :lockExpiredBefore
                    )
                  )
            """)
    int claim(
            @Param("attemptId") UUID attemptId,
            @Param("readyStatuses") List<NotificationAttemptStatus> readyStatuses,
            @Param("processingStatus") NotificationAttemptStatus processingStatus,
            @Param("lockedAt") Instant lockedAt,
            @Param("lockedBy") String lockedBy,
            @Param("lockExpiredBefore") Instant lockExpiredBefore);

    long countByStatus(NotificationAttemptStatus status);

    long deleteByCreatedAtBefore(java.time.Instant createdAtBefore);
}
