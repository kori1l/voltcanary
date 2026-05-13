package com.voltcanary.common.security;

import java.time.Instant;
import java.util.UUID;

public record JwtClaims(
        UUID subject,
        UUID tenantId,
        String email,
        UserRole role,
        Instant issuedAt,
        Instant expiresAt) {
}
