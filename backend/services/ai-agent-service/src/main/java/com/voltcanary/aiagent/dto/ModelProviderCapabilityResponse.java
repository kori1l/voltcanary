package com.voltcanary.aiagent.dto;

import java.util.List;

public record ModelProviderCapabilityResponse(
        String providerType,
        String displayName,
        String authType,
        String defaultBaseUrl,
        String endpointStyle,
        boolean supportsCustomBaseUrl,
        boolean apiKeyRequired,
        boolean supportsJson,
        boolean supportsTools,
        List<SuggestedModelResponse> suggestedModels) {
}
