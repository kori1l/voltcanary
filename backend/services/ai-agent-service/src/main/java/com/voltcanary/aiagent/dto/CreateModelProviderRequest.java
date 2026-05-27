package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateModelProviderRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull String providerType,
        @Size(max = 512) String baseUrl,
        String apiKey,
        @NotBlank @Size(max = 255) String modelId,
        @Size(max = 160) String modelDisplayName,
        BigDecimal temperature,
        BigDecimal topP,
        @Min(128) @Max(32000) Integer maxOutputTokens,
        @Min(1000) @Max(120000) Integer timeoutMs,
        Boolean supportsTools,
        Boolean supportsJson,
        Boolean makeDefault) {
}
