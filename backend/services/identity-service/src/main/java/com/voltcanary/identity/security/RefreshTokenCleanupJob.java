package com.voltcanary.identity.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshTokenCleanupJob {

    private final RefreshTokenService refreshTokenService;

    public RefreshTokenCleanupJob(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    @Scheduled(
            initialDelayString = "${app.auth.refresh-token-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.auth.refresh-token-cleanup.fixed-delay-ms:3600000}")
    @Transactional
    public void deleteExpiredRefreshTokens() {
        refreshTokenService.deleteExpired();
    }
}
