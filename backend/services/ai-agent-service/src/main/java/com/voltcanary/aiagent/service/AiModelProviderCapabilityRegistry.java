package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.dto.ModelProviderCapabilityResponse;
import com.voltcanary.aiagent.dto.SuggestedModelResponse;
import java.util.List;
import org.springframework.stereotype.Component;


@Component
public class AiModelProviderCapabilityRegistry {
    public List<ModelProviderCapabilityResponse> capabilities() {
        return List.of(
                capability("OPENAI", "OpenAI", "https://api.openai.com/v1", "OPENAI_RESPONSES", true, true, true,
                        List.of(
                                new SuggestedModelResponse("gpt-5.4-mini", "GPT-5.4 Mini"),
                                new SuggestedModelResponse("gpt-5.4", "GPT-5.4"),
                                new SuggestedModelResponse("gpt-5.3-codex", "GPT-5.3 Codex"))),
                capability("DEEPSEEK", "DeepSeek", "https://api.deepseek.com", "OPENAI_CHAT_COMPLETIONS", true, true, false,
                        List.of(
                                new SuggestedModelResponse("deepseek-chat", "DeepSeek Chat"),
                                new SuggestedModelResponse("deepseek-reasoner", "DeepSeek Reasoner"))),
                capability("OPENAI_COMPATIBLE", "OpenAI-compatible", "", "OPENAI_CHAT_COMPLETIONS", true, true, false,
                        List.of(new SuggestedModelResponse("custom-model", "Custom model"))),
                capability("ANTHROPIC", "Anthropic Claude", "https://api.anthropic.com", "ANTHROPIC_MESSAGES", true, true, false,
                        List.of(new SuggestedModelResponse("claude-sonnet-4-5", "Claude Sonnet"))),
                capability("GEMINI", "Google Gemini API", "https://generativelanguage.googleapis.com", "GEMINI_GENERATE_CONTENT", true, true, false,
                        List.of(new SuggestedModelResponse("gemini-2.5-flash", "Gemini Flash"))),
                capability("MISTRAL", "Mistral", "https://api.mistral.ai/v1", "OPENAI_CHAT_COMPLETIONS", true, true, false,
                        List.of(new SuggestedModelResponse("mistral-large-latest", "Mistral Large"))));
    }

    public ModelProviderCapabilityResponse capability(AiModelProviderType type) {
        return capabilities().stream()
                .filter(item -> item.providerType().equals(type.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider type " + type));
    }

    private ModelProviderCapabilityResponse capability(
            String providerType,
            String displayName,
            String defaultBaseUrl,
            String endpointStyle,
            boolean supportsCustomBaseUrl,
            boolean supportsJson,
            boolean supportsTools,
            List<SuggestedModelResponse> models) {
        return new ModelProviderCapabilityResponse(
                providerType,
                displayName,
                "API_KEY",
                defaultBaseUrl,
                endpointStyle,
                supportsCustomBaseUrl,
                true,
                supportsJson,
                supportsTools,
                models);
    }
}
