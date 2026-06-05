package com.voltcanary.identity.security;

import com.voltcanary.identity.config.IdentityJwtProperties;
import com.voltcanary.identity.domain.RefreshToken;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate requiresNewTransaction;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTokenTtlSeconds;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            PlatformTransactionManager transactionManager,
            IdentityJwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.refreshTokenTtlSeconds = properties.getRefreshTokenTtlSeconds();
    }

    public IssuedRefreshToken issue(User user) {
        String token = randomToken();
        Instant now = Instant.now();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setId(UUID.randomUUID());
        refreshToken.setUser(user);
        refreshToken.setTokenHash(hash(token));
        refreshToken.setExpiresAt(now.plusSeconds(refreshTokenTtlSeconds));
        refreshToken.setCreatedAt(now);

        refreshTokenRepository.save(refreshToken);
        return new IssuedRefreshToken(token, refreshToken);
    }

    public RefreshToken validate(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hash(token))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));

        if (refreshToken.getRevokedAt() != null) {
            UUID userId = refreshToken.getUser().getId();
            requiresNewTransaction.executeWithoutResult(status -> revokeAllForUser(userId));
            throw new AuthenticationFailedException("Refresh token revoked");
        }
        if (!refreshToken.getExpiresAt().isAfter(Instant.now())) {
            throw new AuthenticationFailedException("Refresh token expired");
        }

        return refreshToken;
    }

    public void revoke(RefreshToken refreshToken) {
        refreshToken.setRevokedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public int revokeAllForUser(UUID userId) {
        return refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
    }

    @Transactional
    public long deleteExpired() {
        return refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash refresh token", exception);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedRefreshToken(String token, RefreshToken entity) {
    }
}
