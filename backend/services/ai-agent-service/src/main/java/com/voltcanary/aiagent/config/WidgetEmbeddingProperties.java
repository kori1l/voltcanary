package com.voltcanary.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai.widget-embeddings")
public record WidgetEmbeddingProperties(boolean enabled, String baseUrl, String model, String apiKey, long timeoutMs) {
    @Override
    public String toString() {
        return "WidgetEmbeddingProperties[enabled=" + enabled + "]";
    }
}
