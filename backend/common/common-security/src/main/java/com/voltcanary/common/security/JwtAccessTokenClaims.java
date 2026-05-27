package com.voltcanary.common.security;

import java.time.Instant;

public record JwtAccessTokenClaims(
        String subject,
        String email,
        String role,
        String issuer,
        String audience,
        String jwtId,
        String keyId,
        Instant issuedAt,
        Instant expiresAt) {
}
