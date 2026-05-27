package com.voltcanary.aiagent.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, String role) {
    public UUID accountId() {
        return userId;
    }

    public boolean isAdmin() {
        return role != null && role.equalsIgnoreCase("ADMIN");
    }
}
