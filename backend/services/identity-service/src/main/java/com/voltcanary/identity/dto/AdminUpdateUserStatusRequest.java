package com.voltcanary.identity.dto;

import com.voltcanary.identity.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateUserStatusRequest(@NotNull UserStatus status) {
}
