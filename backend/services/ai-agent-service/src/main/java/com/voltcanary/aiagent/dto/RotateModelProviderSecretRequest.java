package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotBlank;

public record RotateModelProviderSecretRequest(@NotBlank String apiKey) {
}
