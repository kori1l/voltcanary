package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record PlanRequest(
        @NotBlank String message,
        Map<String, Object> context) {
}
