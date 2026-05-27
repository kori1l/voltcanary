package com.voltcanary.device.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record AccountLifecycleRequest(@Email String email, @NotNull AccountStatus status) {
}
