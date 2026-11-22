package com.voltcanary.common.security;

import java.util.UUID;

/** Verifies user credentials independently of gateway-provided identity headers. */
public final class BearerUserIdentity {

    private final JwtAccessTokenService tokens;

    public BearerUserIdentity(JwtAccessTokenService tokens) {
        this.tokens = tokens;
    }

    public JwtAccessTokenClaims verify(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("Missing bearer access token");
        }
        JwtAccessTokenClaims claims = tokens.validate(authorization.substring(7));
        UUID.fromString(claims.subject());
        return claims;
    }
}
