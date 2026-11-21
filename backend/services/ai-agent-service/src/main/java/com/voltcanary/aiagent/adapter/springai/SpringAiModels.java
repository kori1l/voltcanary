package com.voltcanary.aiagent.adapter.springai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import java.time.Duration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** Per-exchange SDK ownership: credentials, connection lifecycle and options never cross accounts. */
public final class SpringAiModels {
    private final ObservationRegistry observations;

    SpringAiModels(ObservationRegistry observations) {
        this.observations = observations;
    }

    public record Lease(ChatModel model, AutoCloseable client) implements AutoCloseable {
        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception cleanupFailure) {
                // A completed provider exchange must not become retryable because connection cleanup failed.
                com.voltcanary.aiagent.trace.AssistantTrace.event("CAPTURE", "UNKNOWN", "PROVIDER_CLIENT_CLEANUP_FAILED", java.util.Map.of());
            }
        }
    }

    public Lease open(AiResolvedModel selected, long remainingMillis, String schema, boolean tools) {
        var config = selected.modelConfig();
        Double temperature = config.getTemperature() == null ? null : config.getTemperature().doubleValue();
        Double topP = config.getTopP() == null ? null : config.getTopP().doubleValue();
        long configured = config.getTimeoutMs() == null ? 30_000 : Math.max(1_000, config.getTimeoutMs());
        var timeout = Duration.ofMillis(Math.max(1, Math.min(remainingMillis, configured)));
        int output = Math.min(12_000, config.getMaxOutputTokens() == null ? 12_000 : config.getMaxOutputTokens());
        String base = selected.provider().getBaseUrl().replaceAll("/+$", "");
        return switch (selected.provider().getProviderType()) {
            case ANTHROPIC -> {
                var client = org.springframework.ai.anthropic.AnthropicSetup.setupSyncClient(base, selected.apiKey(), timeout, 0, null, java.util.Map.of());
                var options = AnthropicChatOptions.builder().model(config.getModelId()).maxTokens(output)
                        .temperature(temperature).topP(topP).maxRetries(0).timeout(timeout);
                if (schema != null && !tools) options.outputSchema(schema);
                if (tools) options.toolChoice(com.anthropic.models.messages.ToolChoice.ofAny(
                        com.anthropic.models.messages.ToolChoiceAny.builder().disableParallelToolUse(true).build()));
                yield new Lease(AnthropicChatModel.builder().anthropicClient(client).anthropicClientAsync(client.async()).options(options.build()).observationRegistry(observations).build(), client::close);
            }
            case GEMINI -> {
                var http = HttpOptions.builder().baseUrl(base).timeout(Math.toIntExact(timeout.toMillis()))
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
                var client = Client.builder().apiKey(selected.apiKey()).httpOptions(http).build();
                var options = GoogleGenAiChatOptions.builder().model(config.getModelId()).maxOutputTokens(output)
                        .temperature(temperature).topP(topP);
                if (schema != null && !tools) options.responseMimeType("application/json").responseSchema(schema);
                if (tools) options.toolChoice(new GoogleGenAiChatOptions.ToolChoice(
                        GoogleGenAiChatOptions.ToolChoice.Mode.ANY, java.util.List.of()));
                yield new Lease(GoogleGenAiChatModel.builder().genAiClient(client).options(options.build()).observationRegistry(observations)
                        .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build(), client);
            }
            case OPENAI, OPENAI_COMPATIBLE, DEEPSEEK, MISTRAL, XAI, GROQ, OPENROUTER, OLLAMA_LOCAL -> {
                // These registered providers expose the Chat Completions contract.
                var client = new com.openai.client.OpenAIClientImpl(com.openai.core.ClientOptions.builder()
                        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                        .apiKey(selected.apiKey().isBlank() ? "local" : selected.apiKey())
                        .baseUrl(base).timeout(timeout).maxRetries(0).build());
                var options = OpenAiChatOptions.builder().model(config.getModelId()).temperature(temperature)
                        .topP(topP).maxRetries(0).timeout(timeout);
                if (selected.provider().getProviderType() == com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI) options.maxCompletionTokens(output).store(false);
                else options.maxTokens(output);
                if (schema != null && !tools) options.outputSchema(schema);
                if (tools) options.toolChoice("required").parallelToolCalls(false).strict(true);
                yield new Lease(OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options.build()).observationRegistry(observations).build(), client::close);
            }
        };
    }
}
