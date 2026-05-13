package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.LoginAudit;
import com.voltcanary.identity.domain.RefreshToken;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.dto.RegisterRequest;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.exception.DuplicateEmailException;
import com.voltcanary.identity.repository.LoginAuditRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.AuthenticatedUser;
import com.voltcanary.identity.security.JwtService;
import com.voltcanary.identity.security.RefreshTokenService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String DEFAULT_ROLE = "USER";
    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final LoginAuditRepository loginAuditRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            LoginAuditRepository loginAuditRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.loginAuditRepository = loginAuditRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
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
        user.setRole(DEFAULT_ROLE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail());
    }

    @Transactional
    public AuthTokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            auditLogin(null, email, false, "user_not_found");
            throw new AuthenticationFailedException("Invalid email or password");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditLogin(user, email, false, "wrong_password");
            throw new AuthenticationFailedException("Invalid email or password");
        }

        auditLogin(user, email, true, null);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public MeResponse me(String authorizationHeader) {
        AuthenticatedUser user = parseBearerToken(authorizationHeader);
        return new MeResponse(user.id(), user.email(), user.role());
    }

    @Transactional
    public AuthTokenResponse refresh(RefreshTokenRequest request) {
        RefreshToken oldRefreshToken = refreshTokenService.validate(request.refreshToken());
        User user = oldRefreshToken.getUser();
        refreshTokenService.revoke(oldRefreshToken);
        return issueTokens(user);
    }

    private AuthTokenResponse issueTokens(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthTokenResponse(
                jwtService.createAccessToken(user),
                refreshToken.token(),
                TOKEN_TYPE,
                jwtService.accessTokenTtlSeconds());
    }

    private AuthenticatedUser parseBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(TOKEN_TYPE + " ")) {
            throw new AuthenticationFailedException("Missing bearer token");
        }
        return jwtService.parseAccessToken(authorizationHeader.substring((TOKEN_TYPE + " ").length()));
    }

    private void auditLogin(User user, String email, boolean successful, String failureReason) {
        LoginAudit audit = new LoginAudit();
        audit.setId(UUID.randomUUID());
        audit.setUser(user);
        audit.setEmail(email);
        audit.setSuccessful(successful);
        audit.setFailureReason(failureReason);
        audit.setCreatedAt(Instant.now());
        loginAuditRepository.save(audit);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
