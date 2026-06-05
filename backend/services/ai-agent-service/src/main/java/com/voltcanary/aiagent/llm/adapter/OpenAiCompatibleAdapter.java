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
public class OpenAiCompatibleAdapter implements AiModelAdapter {
    private final ObjectMapper objectMapper;
    private final JsonPlanParser parser;

    public OpenAiCompatibleAdapter(ObjectMapper objectMapper, JsonPlanParser parser) {
        this.objectMapper = objectMapper;
        this.parser = parser;
    }

    @Override
    public AiModelProviderType providerType() {
        return AiModelProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public TestModelProviderResponse test(AiResolvedModel model) {
        return new TestModelProviderResponse("READY", "OpenAI-compatible provider is configured.", model.provider().getProviderType().name(), model.modelConfig().getModelId(), 0, Instant.now());
    }

    @Override
    public LlmPlanDraft plan(AiModelPlanRequest request) throws Exception {
        AiModelConfig model = request.resolvedModel().modelConfig();
        RestClient restClient = restClient(request.resolvedModel());

        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("message", request.message());
        userInput.put("context", request.context() == null ? Map.of() : request.context());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelId());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", objectMapper.writeValueAsString(userInput))));
        body.put("max_tokens", model.getMaxOutputTokens() == null ? 1800 : model.getMaxOutputTokens());
        if (model.getTemperature() != null) {
            body.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            body.put("top_p", model.getTopP());
        }
        if (model.isSupportsJson()) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + request.resolvedModel().apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        String text = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText("");
        return parser.parse(request.resolvedModel().provider().getProviderType().name(), model.getModelId(), text);
    }

    private RestClient restClient(AiResolvedModel model) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(model.modelConfig())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(model.modelConfig())));
        return RestClient.builder()
                .baseUrl(model.provider().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private int timeout(AiModelConfig model) {
        return model.getTimeoutMs() == null ? 30000 : Math.max(1000, model.getTimeoutMs());
    }
}
