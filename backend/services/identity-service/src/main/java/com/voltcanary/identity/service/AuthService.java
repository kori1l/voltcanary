package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.EmailVerificationToken;
import com.voltcanary.identity.domain.PasswordResetToken;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.RefreshToken;
import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AdminRefreshSessionResponse;
import com.voltcanary.identity.dto.AdminRevokeSessionsResponse;
import com.voltcanary.identity.dto.AdminUpdateUserRoleRequest;
import com.voltcanary.identity.dto.AdminUpdateUserStatusRequest;
import com.voltcanary.identity.dto.AdminUserResponse;
import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.ChangePasswordRequest;
import com.voltcanary.identity.dto.EmailVerificationConfirmRequest;
import com.voltcanary.identity.dto.EmailVerificationRequest;
import com.voltcanary.identity.dto.ForgotPasswordRequest;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.dto.RegisterRequest;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.dto.ResetPasswordRequest;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.exception.AuthorizationFailedException;
import com.voltcanary.identity.exception.DuplicateEmailException;
import com.voltcanary.identity.repository.EmailVerificationTokenRepository;
import com.voltcanary.identity.repository.PasswordResetTokenRepository;
import com.voltcanary.identity.repository.RefreshTokenRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.AuthenticatedUser;
import com.voltcanary.identity.security.JwtService;
import com.voltcanary.identity.security.RefreshTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final UserRole DEFAULT_ROLE = UserRole.USER;
    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final LoginAuditService loginAuditService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginProtectionService loginProtectionService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long passwordResetTokenTtlSeconds;
    private final long emailVerificationTokenTtlSeconds;

    public AuthService(
            UserRepository userRepository,
            LoginAuditService loginAuditService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            LoginProtectionService loginProtectionService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.auth.password-reset-token-ttl-seconds:${PASSWORD_RESET_TOKEN_TTL_SECONDS:900}}")
                    long passwordResetTokenTtlSeconds,
            @Value("${app.auth.email-verification-token-ttl-seconds:${EMAIL_VERIFICATION_TOKEN_TTL_SECONDS:86400}}")
                    long emailVerificationTokenTtlSeconds) {
        this.userRepository = userRepository;
        this.loginAuditService = loginAuditService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginProtectionService = loginProtectionService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenTtlSeconds = passwordResetTokenTtlSeconds;
        this.emailVerificationTokenTtlSeconds = emailVerificationTokenTtlSeconds;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        Instant now = Instant.now();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmailVerified(false);
        user.setRole(DEFAULT_ROLE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail());
    }

    @Transactional
    public AuthTokenResponse login(LoginRequest request, String clientIp, String userAgent, String correlationId) {
        String email = normalizeEmail(request.email());
        loginProtectionService.checkAllowed(email, clientIp);
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            auditLogin(null, email, false, "user_not_found", clientIp, userAgent, correlationId);
            loginProtectionService.recordFailure(email);
            throw new AuthenticationFailedException("Invalid email or password");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditLogin(user, email, false, "wrong_password", clientIp, userAgent, correlationId);
            loginProtectionService.recordFailure(email);
            throw new AuthenticationFailedException("Invalid email or password");
        }

        ensureActive(user);
        auditLogin(user, email, true, null, clientIp, userAgent, correlationId);
        loginProtectionService.recordSuccess(email);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public MeResponse me(String authorizationHeader) {
        AuthenticatedUser user = parseBearerToken(authorizationHeader);
        User persistedUser = userRepository.findById(user.id())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid access token"));
        return toMeResponse(persistedUser);
    }

    @Transactional
    public AuthTokenResponse refresh(RefreshTokenRequest request) {
        RefreshToken oldRefreshToken = refreshTokenService.validate(request.refreshToken());
        User user = oldRefreshToken.getUser();
        ensureActive(user);
        refreshTokenService.revoke(oldRefreshToken);
        return issueTokens(user);
    }

    @Transactional
    public AuthMessageResponse logout(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenService.validate(request.refreshToken());
        refreshTokenService.revoke(refreshToken);
        return new AuthMessageResponse("logout successful");
    }

    @Transactional
    public AuthMessageResponse logoutAll(String authorizationHeader) {
        AuthenticatedUser user = parseBearerToken(authorizationHeader);
        int revokedCount = refreshTokenService.revokeAllForUser(user.id());
        return new AuthMessageResponse("logout-all successful: revoked " + revokedCount + " refresh tokens");
    }

    @Transactional
    public AuthMessageResponse changePassword(String authorizationHeader, ChangePasswordRequest request) {
        AuthenticatedUser authenticatedUser = parseBearerToken(authorizationHeader);
        User user = userRepository.findById(authenticatedUser.id())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid access token"));
        ensureActive(user);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AuthenticationFailedException("Invalid current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        refreshTokenService.revokeAllForUser(user.getId());
        return new AuthMessageResponse("password changed");
    }

    @Transactional
    public AuthMessageResponse forgotPassword(ForgotPasswordRequest request) {
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
        resetToken.setExpiresAt(now.plusSeconds(passwordResetTokenTtlSeconds));
        passwordResetTokenRepository.save(resetToken);
        return new AuthMessageResponse("password reset requested: " + token);
    }

    @Transactional
    public AuthMessageResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(hash(request.token()))
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
        verificationToken.setExpiresAt(now.plusSeconds(emailVerificationTokenTtlSeconds));
        emailVerificationTokenRepository.save(verificationToken);
        return new AuthMessageResponse("email verification requested: " + token);
    }

    @Transactional
    public AuthMessageResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository.findByTokenHash(hash(request.token()))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid email verification token"));
        Instant now = Instant.now();
        if (verificationToken.getUsedAt() != null || !verificationToken.getExpiresAt().isAfter(now)) {
            throw new AuthenticationFailedException("Invalid email verification token");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        user.setUpdatedAt(now);
        verificationToken.setUsedAt(now);
        return new AuthMessageResponse("email verified");
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(String authorizationHeader) {
        requireAdmin(authorizationHeader);
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .map(this::toAdminUserResponse)
                .toList();
    }

    @Transactional
    public AdminUserResponse updateUserStatus(
            String authorizationHeader,
            UUID userId,
            AdminUpdateUserStatusRequest request) {
        requireAdmin(authorizationHeader);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("User not found"));
        user.setStatus(request.status());
        user.setUpdatedAt(Instant.now());
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse updateUserRole(
            String authorizationHeader,
            UUID userId,
            AdminUpdateUserRoleRequest request) {
        requireAdmin(authorizationHeader);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("User not found"));
        user.setRole(request.role());
        user.setUpdatedAt(Instant.now());
        return toAdminUserResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminRefreshSessionResponse> listUserSessions(String authorizationHeader, UUID userId) {
        requireAdmin(authorizationHeader);
        if (!userRepository.existsById(userId)) {
            throw new AuthenticationFailedException("User not found");
        }
        return refreshTokenRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(RefreshToken::getCreatedAt).reversed())
                .map(this::toAdminRefreshSessionResponse)
                .toList();
    }

    @Transactional
    public AdminRevokeSessionsResponse revokeUserSessions(String authorizationHeader, UUID userId) {
        requireAdmin(authorizationHeader);
        if (!userRepository.existsById(userId)) {
            throw new AuthenticationFailedException("User not found");
        }
        return new AdminRevokeSessionsResponse(refreshTokenService.revokeAllForUser(userId));
    }

    private AuthTokenResponse issueTokens(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthTokenResponse(
                jwtService.createAccessToken(user),
                refreshToken.token(),
                TOKEN_TYPE,
                jwtService.accessTokenTtlSeconds());
    }

    private void requireAdmin(String authorizationHeader) {
        AuthenticatedUser user = parseBearerToken(authorizationHeader);
        if (!UserRole.ADMIN.name().equals(user.role())) {
            throw new AuthorizationFailedException("Admin role is required");
        }
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getStatus(),
                user.isEmailVerified());
    }

    private AdminUserResponse toAdminUserResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private AdminRefreshSessionResponse toAdminRefreshSessionResponse(RefreshToken token) {
        Instant now = Instant.now();
        boolean active = token.getRevokedAt() == null && token.getExpiresAt().isAfter(now);
        return new AdminRefreshSessionResponse(
                token.getId(),
                token.getUser().getId(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getRevokedAt(),
                active);
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
    }

    private AuthenticatedUser parseBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(TOKEN_TYPE + " ")) {
            throw new AuthenticationFailedException("Missing bearer token");
        }
        return jwtService.parseAccessToken(authorizationHeader.substring((TOKEN_TYPE + " ").length()));
    }

    private void auditLogin(
            User user,
            String email,
            boolean successful,
            String failureReason,
            String clientIp,
            String userAgent,
            String correlationId) {
        loginAuditService.record(user, email, successful, failureReason, clientIp, userAgent, correlationId);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash token", exception);
        }
    }
}
