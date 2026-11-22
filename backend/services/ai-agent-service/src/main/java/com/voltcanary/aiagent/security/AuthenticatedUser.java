package com.voltcanary.aiagent.security;

import java.util.UUID;

public record AuthenticatedUser(
    UUID userId,
    String email,
    String role,
    java.time.Instant expiresAt
) {
    public AuthenticatedUser(UUID userId, String email, String role) {
        this(userId, email, role, null);
    }

    public boolean expiresAfter(java.time.Instant deadline) {
        return expiresAt == null || expiresAt.isAfter(deadline);
    }

    public UUID accountId() {
        return userId;
    }

    public boolean isAdmin() {
        return role != null && role.equalsIgnoreCase("ADMIN");
    }
}
