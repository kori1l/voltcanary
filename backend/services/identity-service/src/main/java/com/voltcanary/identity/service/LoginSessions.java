package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.JwtService;
import com.voltcanary.identity.security.RefreshTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks the credential snapshot and commits session creation in one short transaction. */
@Service
@RequiredArgsConstructor
public class LoginSessions {

    private final UserRepository users;
    private final EntityManager entities;
    private final JwtService jwt;
    private final RefreshTokenService refresh;

    @Transactional
    public AuthTokenResponse issue(User authenticated) {
        User current = users
            .findById(authenticated.getId())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password"));
        if (
            current.getStatus() != UserStatus.ACTIVE ||
            !Objects.equals(current.getRowRevision(), authenticated.getRowRevision())
        ) {
            throw new AuthenticationFailedException("Invalid email or password");
        }
        entities.lock(current, LockModeType.OPTIMISTIC);
        var token = refresh.issue(current);
        return new AuthTokenResponse(
            jwt.createAccessToken(current),
            token.token(),
            "Bearer",
            jwt.accessTokenTtlSeconds()
        );
    }
}
