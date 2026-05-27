package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateModelConfigRequest(
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Size(max = 255) String modelId,
        Boolean enabled,
        Boolean makeDefault,
        BigDecimal temperature,
        BigDecimal topP,
        @Min(128) @Max(32000) Integer maxOutputTokens,
        @Min(1000) @Max(120000) Integer timeoutMs,
        Boolean supportsTools,
        Boolean supportsJson,
        String responseFormat) {
}
