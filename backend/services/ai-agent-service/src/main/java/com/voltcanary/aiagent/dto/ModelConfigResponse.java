package com.voltcanary.aiagent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ModelConfigResponse(
        UUID id,
        UUID providerId,
        String displayName,
        String modelId,
        boolean enabled,
        boolean isDefault,
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxOutputTokens,
        Integer timeoutMs,
        boolean supportsTools,
        boolean supportsJson,
        String responseFormat,
        Instant createdAt,
        Instant updatedAt) {
}
