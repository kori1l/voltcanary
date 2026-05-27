package com.voltcanary.identity.dto;

import com.voltcanary.identity.domain.UserRole;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateUserRoleRequest(@NotNull UserRole role) {
}
