package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.provider.AiModelConfig;
import com.voltcanary.aiagent.provider.AiModelProviderType;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiAdapter implements AiModelAdapter {
    private final ObjectMapper objectMapper;
    private final JsonPlanParser parser;

    public OpenAiAdapter(ObjectMapper objectMapper, JsonPlanParser parser) {
        this.objectMapper = objectMapper;
        this.parser = parser;
    }

    @Override
    public AiModelProviderType providerType() {
        return AiModelProviderType.OPENAI;
    }

    @Override
    public TestModelProviderResponse test(AiResolvedModel model) {
        return new TestModelProviderResponse("READY", "OpenAI provider is configured.", providerType().name(), model.modelConfig().getModelId(), 0, Instant.now());
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
        body.put("instructions", request.systemPrompt());
        body.put("input", objectMapper.writeValueAsString(userInput));
        body.put("max_output_tokens", model.getMaxOutputTokens() == null ? 1800 : model.getMaxOutputTokens());
        if (model.getTemperature() != null) {
            body.put("temperature", model.getTemperature());
        }

        JsonNode response = restClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + request.resolvedModel().apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return parser.parse(providerType().name(), model.getModelId(), outputText(response));
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

    private String outputText(JsonNode response) {
        if (response == null) return "";
        if (response.path("output_text").isTextual()) {
            return response.path("output_text").asText();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if (content.path("text").isTextual()) {
                    text.append(content.path("text").asText());
                }
            }
        }
        return text.isEmpty() ? "" : text.toString();
    }
}
