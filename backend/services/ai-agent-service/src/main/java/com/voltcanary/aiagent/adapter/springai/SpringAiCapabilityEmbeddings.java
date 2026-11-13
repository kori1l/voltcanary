package com.voltcanary.aiagent.adapter.springai;

import com.voltcanary.aiagent.config.WidgetEmbeddingProperties;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.port.CapabilityEmbeddings;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** One bounded SDK exchange, without retries or account-provider credential reuse. */
public final class SpringAiCapabilityEmbeddings implements CapabilityEmbeddings {
    private final WidgetEmbeddingProperties properties;

    public SpringAiCapabilityEmbeddings(WidgetEmbeddingProperties properties) {
        if (properties.baseUrl() == null || properties.model() == null || properties.model().isBlank()
                || properties.timeoutMs() < 100 || properties.timeoutMs() > 5000) {
            throw new IllegalArgumentException("Widget embeddings require endpoint, model and timeout within 100..5000 ms");
        }
        var uri = java.net.URI.create(properties.baseUrl());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid widget embedding endpoint");
        }
        this.properties = properties;
    }

    @Override
    public String identity() {
        return SkillRegistry.digest(properties.baseUrl() + ":" + properties.model());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty() || texts.size() > 512 || texts.stream().anyMatch(text -> text == null || text.length() > 4096)) {
            throw new IllegalArgumentException("Embedding input bounds");
        }
        var timeout = Duration.ofMillis(properties.timeoutMs());
        String key = properties.apiKey() == null || properties.apiKey().isBlank() ? "local" : properties.apiKey();
        var client = new com.openai.client.OpenAIClientImpl(com.openai.core.ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .apiKey(key).baseUrl(properties.baseUrl()).timeout(timeout).maxRetries(0).build());
        try {
            return OpenAiEmbeddingModel.builder().openAiClient(client)
                    .options(OpenAiEmbeddingOptions.builder().model(properties.model()).build()).build().embed(texts);
        } finally {
            client.close();
        }
    }
}
