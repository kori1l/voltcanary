package com.voltcanary.aiagent.dto;

import java.time.Instant;

public record TestModelProviderResponse(
        String status,
        String message,
        String providerType,
        String modelId,
        Integer latencyMs,
        Instant testedAt) {
}
