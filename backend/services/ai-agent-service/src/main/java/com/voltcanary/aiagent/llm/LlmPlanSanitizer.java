package com.voltcanary.aiagent.llm;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LlmPlanSanitizer {
    private static final Map<AgentTool, Set<String>> ALLOWED_PARAMETERS = new EnumMap<>(AgentTool.class);

    static {
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DEVICE, Set.of("name", "type", "locationId", "manufacturer", "model"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DATA_SOURCE, Set.of("deviceId", "sourceType", "protocol", "externalId"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_SIMULATOR_CONFIG, Set.of(
                "deviceId", "dataSourceId", "name", "profile", "protocol", "sourceId", "yamlConfig"));
        ALLOWED_PARAMETERS.put(AgentTool.VALIDATE_SIMULATOR_YAML, Set.of("deviceId", "yamlConfig"));
    }

    public AgentPlan sanitize(AuthenticatedUser user, String message, LlmPlanDraft draft, AgentPlan fallback) {
        List<AgentAction> actions = new ArrayList<>();
        for (LlmActionDraft actionDraft : safeActions(draft)) {
            AgentTool tool = parseTool(actionDraft.tool());
            if (tool == null) {
                continue;
            }
            Map<String, Object> parameters = mergedParameters(tool, actionDraft.parameters(), fallback);
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    tool,
                    textOrFallback(actionDraft.title(), fallbackTitle(tool)),
                    textOrFallback(actionDraft.description(), fallbackDescription(tool)),
                    tool != AgentTool.VALIDATE_SIMULATOR_YAML,
                    parameters));
        }

        if (actions.isEmpty()) {
            actions = fallback.actions();
        }

        List<String> safetyNotes = new ArrayList<>(fallback.safetyNotes());
        safetyNotes.add("LLM output is validated against the allowed tool list before it becomes executable.");
        safetyNotes.add("Provider: " + textOrFallback(draft.provider(), "openai") + ", model: " + textOrFallback(draft.model(), "configured model") + ".");

        return new AgentPlan(
                UUID.randomUUID(),
                user.userId(),
                message,
                textOrFallback(draft.summary(), fallback.summary()),
                textOrFallback(draft.explanation(), fallback.explanation()),
                safetyNotes,
                actions,
                Instant.now());
    }

    private List<LlmActionDraft> safeActions(LlmPlanDraft draft) {
        return draft.actions() == null ? List.of() : draft.actions();
    }

    private AgentTool parseTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return null;
        }
        try {
            return AgentTool.valueOf(tool.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Map<String, Object> mergedParameters(AgentTool tool, Map<String, Object> llmParameters, AgentPlan fallback) {
        Map<String, Object> parameters = new LinkedHashMap<>(fallbackParameters(tool, fallback));
        Set<String> allowed = ALLOWED_PARAMETERS.getOrDefault(tool, Set.of());
        if (llmParameters != null) {
            for (Map.Entry<String, Object> entry : llmParameters.entrySet()) {
                if (allowed.contains(entry.getKey()) && entry.getValue() != null) {
                    parameters.put(entry.getKey(), entry.getValue());
                }
            }
        }
        parameters.remove("accountId");
        return parameters;
    }

    private Map<String, Object> fallbackParameters(AgentTool tool, AgentPlan fallback) {
        return fallback.actions().stream()
                .filter(action -> action.tool() == tool)
                .findFirst()
                .map(AgentAction::parameters)
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
    }

    private String fallbackTitle(AgentTool tool) {
        return switch (tool) {
            case CREATE_DEVICE -> "Create device";
            case CREATE_DATA_SOURCE -> "Create simulator data source";
            case CREATE_SIMULATOR_CONFIG -> "Create simulator config";
            case VALIDATE_SIMULATOR_YAML -> "Validate simulator YAML";
        };
    }

    private String fallbackDescription(AgentTool tool) {
        return switch (tool) {
            case CREATE_DEVICE -> "Create a device through device-service using the current user's authorization.";
            case CREATE_DATA_SOURCE -> "Attach a SIMULATOR data source to the target device through device-service.";
            case CREATE_SIMULATOR_CONFIG -> "Create simulator YAML through simulator-service.";
            case VALIDATE_SIMULATOR_YAML -> "Validate simulator YAML without changing resources.";
        };
    }

    private String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
