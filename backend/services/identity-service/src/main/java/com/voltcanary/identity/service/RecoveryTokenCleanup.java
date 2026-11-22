package com.voltcanary.identity.service;

import com.voltcanary.identity.repository.EmailVerificationTokenRepository;
import com.voltcanary.identity.repository.PasswordResetTokenRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecoveryTokenCleanup {

    private final PasswordResetTokenRepository resets;
    private final EmailVerificationTokenRepository verifications;

    @Transactional
    public int deleteExpired() {
        Instant now = Instant.now();
        return resets.deleteExpired(now) + verifications.deleteExpired(now);
    }
}
