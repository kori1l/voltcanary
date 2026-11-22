package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityAuthProperties;
import com.voltcanary.identity.domain.EmailVerificationToken;
import com.voltcanary.identity.domain.PasswordResetToken;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.EmailVerificationConfirmRequest;
import com.voltcanary.identity.dto.EmailVerificationRequest;
import com.voltcanary.identity.dto.ForgotPasswordRequest;
import com.voltcanary.identity.dto.ResetPasswordRequest;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.repository.EmailVerificationTokenRepository;
import com.voltcanary.identity.repository.PasswordResetTokenRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.RefreshTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns one-time credential consumption and atomic recovery-delivery creation. */
@Service
@RequiredArgsConstructor
public class AccountRecoveryService {

    private final UserRepository userRepository;
    private final RecoveryMail recoveryMail;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final IdentityAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public AuthMessageResponse forgotPassword(ForgotPasswordRequest request) {
        recoveryMail.requireConfigured();
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return new AuthMessageResponse("password reset requested");
        }

        String token = randomToken();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setId(UUID.randomUUID());
        resetToken.setUser(user);
        resetToken.setTokenHash(hash(token));
        Instant now = Instant.now();
        resetToken.setCreatedAt(now);
        resetToken.setExpiresAt(now.plusSeconds(properties.getPasswordResetTokenTtlSeconds()));
        passwordResetTokenRepository.save(resetToken);
        recoveryMail.queue(user.getEmail(), "password reset", token);
        return new AuthMessageResponse("password reset requested");
    }

    @Transactional
    public AuthMessageResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository
            .findByTokenHash(hash(request.token()))
            .orElseThrow(() -> new AuthenticationFailedException("Invalid password reset token"));
        Instant now = Instant.now();
        if (resetToken.getUsedAt() != null || !resetToken.getExpiresAt().isAfter(now)) {
            throw new AuthenticationFailedException("Invalid password reset token");
        }

        User user = resetToken.getUser();
        ensureActive(user);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(now);
        resetToken.setUsedAt(now);
        refreshTokenService.revokeAllForUser(user.getId());
        return new AuthMessageResponse("password reset complete");
    }

    @Transactional
    public AuthMessageResponse requestEmailVerification(EmailVerificationRequest request) {
        recoveryMail.requireConfigured();
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isEmailVerified()) {
            return new AuthMessageResponse("email verification requested");
        }

        String token = randomToken();
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setId(UUID.randomUUID());
        verificationToken.setUser(user);
        verificationToken.setTokenHash(hash(token));
        Instant now = Instant.now();
        verificationToken.setCreatedAt(now);
        verificationToken.setExpiresAt(
            now.plusSeconds(properties.getEmailVerificationTokenTtlSeconds())
        );
        emailVerificationTokenRepository.save(verificationToken);
        recoveryMail.queue(user.getEmail(), "email verification", token);
        return new AuthMessageResponse("email verification requested");
    }

    @Transactional
    public AuthMessageResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository
            .findByTokenHash(hash(request.token()))
            .orElseThrow(() ->
                new AuthenticationFailedException("Invalid email verification token")
            );
        Instant now = Instant.now();
        if (
            verificationToken.getUsedAt() != null || !verificationToken.getExpiresAt().isAfter(now)
        ) {
            throw new AuthenticationFailedException("Invalid email verification token");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        user.setUpdatedAt(now);
        verificationToken.setUsedAt(now);
        return new AuthMessageResponse("email verified");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                token.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash token", exception);
        }
    }
}
