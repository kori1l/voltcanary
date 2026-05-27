package com.voltcanary.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminRefreshSessionResponse(
        UUID id,
        UUID userId,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean active) {
}
