package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.repository.UserRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class PasswordLogin {

    private final UserRepository userRepository;
    private final LoginAuditService loginAuditService;
    private final PasswordEncoder passwordEncoder;
    private final LoginProtectionService loginProtectionService;
    private final LoginSessions loginSessions;

    @Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    public AuthTokenResponse login(
        LoginRequest request,
        String clientIp,
        String userAgent,
        String correlationId
    ) {
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
        return loginSessions.issue(user);
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
    }

    private void auditLogin(
        User user,
        String email,
        boolean successful,
        String failureReason,
        String clientIp,
        String userAgent,
        String correlationId
    ) {
        loginAuditService.record(
            user,
            email,
            successful,
            failureReason,
            clientIp,
            userAgent,
            correlationId
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
