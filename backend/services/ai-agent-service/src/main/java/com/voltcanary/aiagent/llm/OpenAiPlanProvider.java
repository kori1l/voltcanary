package com.voltcanary.aiagent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class OpenAiPlanProvider implements LlmPlanProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiPlanProvider.class);
    private static final String SYSTEM_PROMPT = """
            You are the VoltCanary AI Agent planner.
            Return JSON only. Do not include markdown.
            You can only propose these tools:
            - CREATE_DEVICE
            - CREATE_DATA_SOURCE
            - CREATE_SIMULATOR_CONFIG
            - VALIDATE_SIMULATOR_YAML
            Never include accountId, tenantId, admin credentials, direct database access, or telemetry table access.
            Do not execute anything. Create a concise plan that downstream services will validate and execute later.
            JSON schema:
            {
              "summary": "short user-visible summary",
              "explanation": "what will be done and why confirmation may be required",
              "actions": [
                {
                  "tool": "CREATE_DEVICE",
                  "title": "short title",
                  "description": "short description",
                  "parameters": {}
                }
              ]
            }
            """;
    private static final TypeReference<Map<String, Object>> PARAMETERS_TYPE = new TypeReference<>() {
    };

    private final AiAgentProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiPlanProvider(AiAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<LlmPlanDraft> plan(String message, Map<String, Object> context) {
        if (!"openai".equalsIgnoreCase(properties.getProvider()) || properties.getOpenai().getApiKey().isBlank()) {
            return Optional.empty();
        }

        try {
            String text = callResponsesApi(message, context == null ? Map.of() : context);
            JsonNode root = objectMapper.readTree(extractJson(text));
            List<LlmActionDraft> actions = new ArrayList<>();
            for (JsonNode actionNode : root.path("actions")) {
                actions.add(new LlmActionDraft(
                        actionNode.path("tool").asText(),
                        actionNode.path("title").asText(null),
                        actionNode.path("description").asText(null),
                        objectMapper.convertValue(actionNode.path("parameters"), PARAMETERS_TYPE)));
            }
            return Optional.of(new LlmPlanDraft(
                    "openai",
                    properties.getOpenai().getModel(),
                    root.path("summary").asText(null),
                    root.path("explanation").asText(null),
                    actions));
        } catch (Exception ex) {
            log.warn("OpenAI plan provider failed, falling back to rules: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String callResponsesApi(String message, Map<String, Object> context) throws Exception {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1000, properties.getOpenai().getTimeoutMs()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getOpenai().getBaseUrl())
                .requestFactory(requestFactory)
                .build();

        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("message", message);
        userInput.put("context", context);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getOpenai().getModel());
        request.put("instructions", SYSTEM_PROMPT);
        request.put("input", objectMapper.writeValueAsString(userInput));
        request.put("max_output_tokens", 1800);

        JsonNode response = restClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        String outputText = outputText(response);
        if (outputText.isBlank()) {
            throw new RestClientException("empty OpenAI response text");
        }
        return outputText;
    }

    private String outputText(JsonNode response) {
        if (response == null) {
            return "";
        }
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
        if (!text.isEmpty()) {
            return text.toString();
        }
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM output does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
