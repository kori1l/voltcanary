package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.llm.LlmActionDraft;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonPlanParser {

    private static final TypeReference<Map<String, Object>> PARAMETERS_TYPE =
        new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public LlmPlanDraft parse(String provider, String model, String text) throws Exception {
        try (
            var parsing = com.voltcanary.aiagent.trace.AssistantTrace.start(
                "PARSING",
                Map.of("provider", provider, "model", model)
            )
        ) {
            try {
                var draft = parseDocument(provider, model, text);
                parsing.success(Map.of());
                return draft;
            } catch (Exception failure) {
                parsing.failure(
                    "MODEL_OUTPUT_CONTRACT",
                    Map.of("failureType", failure.getClass().getSimpleName())
                );
                throw failure;
            }
        }
    }

    private LlmPlanDraft parseDocument(
        String provider,
        String model,
        String text
    ) throws Exception {
        com.voltcanary.aiagent.trace.AssistantTrace.event(
            "PROVIDER_RESPONSE",
            "RECEIVED",
            null,
            Map.of("provider", provider, "model", model)
        );
        JsonNode root = objectMapper.readTree(extractJson(text));
        com.voltcanary.aiagent.trace.AssistantTrace.event(
            "CANDIDATE",
            "OBSERVED",
            null,
            Map.of(
                "actions",
                objectMapper.convertValue(root.path("actions"), Object.class) == null
                    ? List.of()
                    : objectMapper.convertValue(root.path("actions"), Object.class)
            )
        );
        if (
            !root.isObject() ||
            !root.path("explanation").isTextual() ||
            root.path("explanation").asText().isBlank()
        ) {
            throw new IllegalArgumentException(
                "Model response must include a user-visible explanation"
            );
        }
        List<LlmActionDraft> actions = new ArrayList<>();
        for (JsonNode actionNode : root.path("actions")) {
            actions.add(
                new LlmActionDraft(
                    actionNode.path("tool").asText(),
                    actionNode.path("title").asText(null),
                    actionNode.path("description").asText(null),
                    objectMapper.convertValue(actionNode.path("parameters"), PARAMETERS_TYPE)
                )
            );
        }
        if (
            root.path("clarification").path("suggestedValues") instanceof
                com.fasterxml.jackson.databind.node.ObjectNode suggestions
        ) {
            var empty = new ArrayList<String>();
            suggestions.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull()) empty.add(entry.getKey());
            });
            suggestions.remove(empty);
        }
        var question = root.hasNonNull("clarification")
            ? objectMapper.treeToValue(
                  root.get("clarification"),
                  com.voltcanary.aiagent.model.PlanningQuestion.class
              )
            : null;
        return new LlmPlanDraft(
            provider,
            model,
            root.path("summary").asText(null),
            root.path("explanation").asText(null),
            actions,
            question
        );
    }

    public String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM output does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
