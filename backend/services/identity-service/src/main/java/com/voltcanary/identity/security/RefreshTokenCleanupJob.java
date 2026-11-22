package com.voltcanary.identity.security;

import com.voltcanary.identity.scheduling.SchedulerLockService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private static final String LOCK_NAME = "identity.refresh-token-cleanup";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(30);

    private final RefreshTokenService refreshTokenService;
    private final com.voltcanary.identity.service.RecoveryTokenCleanup recoveryTokens;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(
            initialDelayString = "${app.auth.refresh-token-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.auth.refresh-token-cleanup.fixed-delay-ms:3600000}")
    public void deleteExpiredRefreshTokens() {
        if (!schedulerLockService.acquire(LOCK_NAME, LOCK_LEASE)) {
            return;
        }
        try {
            refreshTokenService.deleteExpired();
            recoveryTokens.deleteExpired();
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }
}
