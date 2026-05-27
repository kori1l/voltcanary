package com.voltcanary.identity.dto;

import com.voltcanary.identity.domain.UserStatus;
import java.util.UUID;

public record MeResponse(UUID id, String email, String role, UserStatus status, boolean emailVerified) {
}
