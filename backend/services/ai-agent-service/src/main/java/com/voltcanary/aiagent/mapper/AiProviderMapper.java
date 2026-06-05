package com.voltcanary.aiagent.mapper;

import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.dto.ModelConfigResponse;
import com.voltcanary.aiagent.dto.ModelProviderResponse;
import java.util.List;


public final class AiProviderMapper {
    private AiProviderMapper() {
    }

    public static ModelProviderResponse provider(AiModelProvider provider, List<ModelConfigResponse> models) {
        return new ModelProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getProviderType().name(),
                provider.getStatus().name(),
                provider.isDefaultProvider(),
                provider.getBaseUrl(),
                provider.getAuthType().name(),
                provider.getMaskedSecretHint(),
                provider.getLastTestStatus(),
                provider.getLastTestMessage(),
                provider.getLastTestAt(),
                models,
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }

    public static ModelConfigResponse model(AiModelConfig model) {
        return new ModelConfigResponse(
                model.getId(),
                model.getProviderId(),
                model.getDisplayName(),
                model.getModelId(),
                model.isEnabled(),
                model.isDefaultModel(),
                model.getTemperature(),
                model.getTopP(),
                model.getMaxOutputTokens(),
                model.getTimeoutMs(),
                model.isSupportsTools(),
                model.isSupportsJson(),
                model.getResponseFormat(),
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
