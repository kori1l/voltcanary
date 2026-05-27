package com.voltcanary.aiagent.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, String role) {
}
