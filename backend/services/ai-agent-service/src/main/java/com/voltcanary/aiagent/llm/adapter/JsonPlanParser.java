package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.llm.LlmActionDraft;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonPlanParser {
    private static final TypeReference<Map<String, Object>> PARAMETERS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JsonPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LlmPlanDraft parse(String provider, String model, String text) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(text));
        List<LlmActionDraft> actions = new ArrayList<>();
        for (JsonNode actionNode : root.path("actions")) {
            actions.add(new LlmActionDraft(
                    actionNode.path("tool").asText(),
                    actionNode.path("title").asText(null),
                    actionNode.path("description").asText(null),
                    objectMapper.convertValue(actionNode.path("parameters"), PARAMETERS_TYPE)));
        }
        return new LlmPlanDraft(provider, model, root.path("summary").asText(null), root.path("explanation").asText(null), actions);
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
