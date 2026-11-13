package com.voltcanary.aiagent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
            com.voltcanary.aiagent.trace.AssistantTrace.event("CANDIDATE", "OBSERVED", null,
                    Map.of("actions", objectMapper.convertValue(root.path("actions"), Object.class)));
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
            com.voltcanary.aiagent.trace.AssistantTrace.event("PARSING", "FAILED", "LEGACY_PROVIDER_FAILURE", Map.of("failureType", ex.getClass().getSimpleName()));
            log.warn("OpenAI plan provider failed, falling back to rules: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String callResponsesApi(String message, Map<String, Object> context) throws Exception {
        var settings = properties.getOpenai();
        var provider = new com.voltcanary.aiagent.domain.AiModelProvider();
        provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
        provider.setBaseUrl(settings.getBaseUrl());
        var config = new com.voltcanary.aiagent.domain.AiModelConfig();
        config.setModelId(settings.getModel());
        config.setMaxOutputTokens(1800);
        config.setTimeoutMs(settings.getTimeoutMs());
        var selected = new com.voltcanary.aiagent.llm.adapter.AiResolvedModel(provider, config, settings.getApiKey());
        try (var trace = com.voltcanary.aiagent.trace.AssistantTrace.start("MODEL_CALL", Map.of("provider", "openai", "model", settings.getModel()))) {
            try {
                var result = new com.voltcanary.aiagent.adapter.springai.SpringAiExchange(objectMapper).call(selected,
                        SYSTEM_PROMPT, objectMapper.writeValueAsString(Map.of("message", message, "context", context)), null, false, settings.getTimeoutMs());
                if (result.failure() != null) throw new IllegalArgumentException(result.failure());
                trace.success(Map.of());
                return result.text();
            } catch (Exception failure) {
                trace.failure("PROVIDER_FAILED", Map.of("failureType", failure.getClass().getSimpleName()));
                throw failure;
            }
        }
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
