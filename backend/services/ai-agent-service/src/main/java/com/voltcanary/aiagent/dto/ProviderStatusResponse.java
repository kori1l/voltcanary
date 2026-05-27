package com.voltcanary.aiagent.dto;

public record ProviderStatusResponse(
        String provider,
        String model,
        boolean enabled,
        String fallback) {
}
