package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class GeminiAdapter implements AiModelAdapter {
    private final ObjectMapper objectMapper;
    private final JsonPlanParser parser;

    public GeminiAdapter(ObjectMapper objectMapper, JsonPlanParser parser) {
        this.objectMapper = objectMapper;
        this.parser = parser;
    }

    @Override
    public AiModelProviderType providerType() {
        return AiModelProviderType.GEMINI;
    }

    @Override
    public TestModelProviderResponse test(AiResolvedModel model) {
        return new TestModelProviderResponse("READY", "Gemini provider is configured.", providerType().name(), model.modelConfig().getModelId(), 0, Instant.now());
    }

    @Override
    public LlmPlanDraft plan(AiModelPlanRequest request) throws Exception {
        AiModelConfig model = request.resolvedModel().modelConfig();
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("message", request.message());
        userInput.put("context", request.context() == null ? Map.of() : request.context());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemPrompt()))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", objectMapper.writeValueAsString(userInput))))));
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", model.getMaxOutputTokens() == null ? 1800 : model.getMaxOutputTokens());
        if (model.getTemperature() != null) {
            generationConfig.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            generationConfig.put("topP", model.getTopP());
        }
        body.put("generationConfig", generationConfig);

        JsonNode response = restClient(request.resolvedModel()).post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", model.getModelId(), request.resolvedModel().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return parser.parse(providerType().name(), model.getModelId(), outputText(response));
    }

    private RestClient restClient(AiResolvedModel model) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(model.modelConfig())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(model.modelConfig())));
        return RestClient.builder().baseUrl(model.provider().getBaseUrl()).requestFactory(requestFactory).build();
    }

    private int timeout(AiModelConfig model) {
        return model.getTimeoutMs() == null ? 30000 : Math.max(1000, model.getTimeoutMs());
    }

    private String outputText(JsonNode response) {
        if (response == null) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            }
        }
        return text.toString();
    }
}
