package com.voltcanary.device.dto;

import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.domain.Device;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;


public record AccountLifecycleRequest(@Email String email, @NotNull AccountStatus status) {
}
