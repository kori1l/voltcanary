package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

public record PlanRequest(
        @NotBlank String message,
        Map<String, Object> context,
        UUID providerId,
        UUID modelConfigId) {
}
