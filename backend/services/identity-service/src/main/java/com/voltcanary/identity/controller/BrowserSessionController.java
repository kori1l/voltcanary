package com.voltcanary.identity.controller;

import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import com.voltcanary.identity.security.BrowserSessionCookies;
import com.voltcanary.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/browser/{sessionId}")
@RequiredArgsConstructor
public class BrowserSessionController {

    public record SessionTokens(
        UUID sessionId,
        String accessToken,
        String tokenType,
        long expiresIn
    ) {}

    private final AuthService auth;
    private final BrowserSessionCookies cookies;

    @PostMapping("/login")
    public SessionTokens login(
        @PathVariable UUID sessionId,
        @Valid @RequestBody LoginRequest input,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        cookies.requireTrustedOrigin(request);
        var tokens = auth.login(
            input,
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            request.getHeader("X-Correlation-Id")
        );
        return issue(sessionId, tokens, response);
    }

    @PostMapping("/refresh")
    public SessionTokens refresh(
        @PathVariable UUID sessionId,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        cookies.requireTrustedOrigin(request);
        try {
            return issue(
                sessionId,
                auth.refresh(new RefreshTokenRequest(cookies.read(request))),
                response
            );
        } catch (AuthenticationFailedException invalid) {
            cookies.clear(sessionId, response);
            throw invalid;
        }
    }

    @PostMapping("/logout")
    public AuthMessageResponse logout(
        @PathVariable UUID sessionId,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        cookies.requireTrustedOrigin(request);
        try {
            return auth.logout(new RefreshTokenRequest(cookies.read(request)));
        } finally {
            cookies.clear(sessionId, response);
        }
    }

    private SessionTokens issue(
        UUID sessionId,
        AuthTokenResponse tokens,
        HttpServletResponse response
    ) {
        cookies.issue(sessionId, tokens.refreshToken(), response);
        return new SessionTokens(
            sessionId,
            tokens.accessToken(),
            tokens.tokenType(),
            tokens.expiresIn()
        );
    }
}
