package com.voltcanary.common.security;

import java.util.UUID;

public record CurrentUser(
        UUID userId,
        UUID tenantId,
        String email,
        UserRole role) {
}
