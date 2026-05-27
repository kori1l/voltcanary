package com.voltcanary.identity.dto;

import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt) {
}
