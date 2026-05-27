package com.voltcanary.aiagent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ModelProviderResponse(
        UUID id,
        String name,
        String providerType,
        String status,
        boolean isDefault,
        String baseUrl,
        String authType,
        String maskedSecretHint,
        String lastTestStatus,
        String lastTestMessage,
        Instant lastTestAt,
        List<ModelConfigResponse> models,
        Instant createdAt,
        Instant updatedAt) {
}
