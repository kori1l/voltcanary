package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.RefreshToken;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
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
import com.voltcanary.identity.repository.RefreshTokenRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.AuthenticatedUser;
import com.voltcanary.identity.security.JwtService;
import com.voltcanary.identity.security.RefreshTokenService;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@lombok.RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final AccountRecoveryService recovery;
    private final UserRegistrationService registration;
    private final PasswordLogin passwordLogin;
    private final LoginSessions loginSessions;
    private final LoginAuditService loginAuditService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginProtectionService loginProtectionService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final IdentityLifecycleOutboxService identityLifecycleOutboxService;

    public RegisterResponse register(RegisterRequest request) {
        return registration.register(request);
    }

    public AuthTokenResponse login(
        LoginRequest request,
        String clientIp,
        String userAgent,
        String correlationId
    ) {
        return passwordLogin.login(request, clientIp, userAgent, correlationId);
    }

    @Transactional(readOnly = true)
    public MeResponse me(String authorizationHeader) {
        AuthenticatedUser user = jwtService.parseBearerToken(authorizationHeader);
        User persistedUser = userRepository
            .findById(user.id())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid access token"));
        ensureActive(persistedUser);
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
        AuthenticatedUser user = jwtService.parseBearerToken(authorizationHeader);
        int revokedCount = refreshTokenService.revokeAllForUser(user.id());
        return new AuthMessageResponse(
            "logout-all successful: revoked " + revokedCount + " refresh tokens"
        );
    }

    @Transactional
    public AuthMessageResponse changePassword(
        String authorizationHeader,
        ChangePasswordRequest request
    ) {
        AuthenticatedUser authenticatedUser = jwtService.parseBearerToken(authorizationHeader);
        User user = userRepository
            .findById(authenticatedUser.id())
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

    public AuthMessageResponse forgotPassword(ForgotPasswordRequest request) {
        return recovery.forgotPassword(request);
    }

    public AuthMessageResponse resetPassword(ResetPasswordRequest request) {
        return recovery.resetPassword(request);
    }

    public AuthMessageResponse requestEmailVerification(EmailVerificationRequest request) {
        return recovery.requestEmailVerification(request);
    }

    public AuthMessageResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        return recovery.confirmEmailVerification(request);
    }

    private AuthTokenResponse issueTokens(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthTokenResponse(
            jwtService.createAccessToken(user),
            refreshToken.token(),
            TOKEN_TYPE,
            jwtService.accessTokenTtlSeconds()
        );
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(
            user.getId(),
            user.getEmail(),
            user.getRole().name(),
            user.getStatus(),
            user.isEmailVerified()
        );
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
    }
}
