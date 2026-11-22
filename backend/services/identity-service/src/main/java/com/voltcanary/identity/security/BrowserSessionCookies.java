package com.voltcanary.identity.security;

import com.voltcanary.identity.config.BrowserSessionProperties;
import com.voltcanary.identity.config.IdentityJwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Browser refresh credentials are host-only, HttpOnly and isolated by public session path. */
@Component
@RequiredArgsConstructor
public class BrowserSessionCookies {

    public static final String NAME = "voltcanary.browser-refresh";
    private final BrowserSessionProperties properties;
    private final IdentityJwtProperties jwt;

    public void requireTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || properties.getAllowedOrigins().stream().noneMatch(origin::equals)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Browser session origin is not allowed"
            );
        }
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() != null) {
            String token = null;
            for (var cookie : request.getCookies()) {
                if (NAME.equals(cookie.getName())) {
                    if (token != null) throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Ambiguous browser session"
                    );
                    token = cookie.getValue();
                }
            }
            if (token != null && !token.isBlank()) return token;
        }
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Browser session is unavailable"
        );
    }

    public void issue(UUID sessionId, String token, HttpServletResponse response) {
        write(sessionId, token, Duration.ofSeconds(jwt.getRefreshTokenTtlSeconds()), response);
    }

    public void clear(UUID sessionId, HttpServletResponse response) {
        write(sessionId, "", Duration.ZERO, response);
    }

    private void write(UUID sessionId, String value, Duration age, HttpServletResponse response) {
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.isSecureCookie())
                .sameSite("Strict")
                .path("/auth/browser/" + sessionId)
                .maxAge(age)
                .build()
                .toString()
        );
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
