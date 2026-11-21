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
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DEVICE, Set.of("name", "type", "locationId", "deviceTypeRef", "locationRef", "manufacturer", "model"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DASHBOARD, Set.of("protocol", "operations", "name", "templateRef", "templateVersion", "deviceRefs", "sourceRefs", "metricRefs", "period", "panelLayout", "widgets", "widgetEdits", "addWidgets", "widgetOrder", "moveWidgets", "restoreSteps"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DATA_SOURCE, Set.of("deviceId", "sourceType", "protocol", "externalId"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_SIMULATOR_CONFIG, Set.of(
                "deviceId", "dataSourceId", "name", "profile", "protocol", "sourceId", "yamlConfig"));
        ALLOWED_PARAMETERS.put(AgentTool.VALIDATE_SIMULATOR_YAML, Set.of("deviceId", "yamlConfig"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_ALERT_RULE, Set.of(
                "name", "description", "ruleType", "enabled", "scopeType", "scopeId", "deviceType",
                "metric", "conditionType", "threshold", "thresholdUnit", "baselineMultiplier", "minPoints",
                "severity", "cooldownSeconds", "deduplicationWindowSeconds", "notificationChannelIds", "config"));
        ALLOWED_PARAMETERS.put(AgentTool.UPDATE_ALERT_RULE, Set.of(
                "ruleId", "name", "description", "ruleType", "enabled", "scopeType", "scopeId", "deviceType",
                "metric", "conditionType", "threshold", "thresholdUnit", "baselineMultiplier", "minPoints",
                "severity", "cooldownSeconds", "deduplicationWindowSeconds", "notificationChannelIds", "config"));
        ALLOWED_PARAMETERS.put(AgentTool.TEST_ALERT_RULE, Set.of(
                "deviceId", "from", "to", "name", "description", "ruleType", "enabled", "scopeType", "scopeId",
                "deviceType", "metric", "conditionType", "threshold", "thresholdUnit", "baselineMultiplier",
                "minPoints", "severity", "cooldownSeconds", "deduplicationWindowSeconds", "notificationChannelIds",
                "config"));
        ALLOWED_PARAMETERS.put(AgentTool.EXPLAIN_ALERT, Set.of("alertId"));
        ALLOWED_PARAMETERS.put(AgentTool.OPEN_TELEMETRY_WINDOW, Set.of("deviceId", "metric", "from", "to", "alertId"));
        ALLOWED_PARAMETERS.put(AgentTool.CREATE_DEMO_DEVICE, Set.of(
                "name", "type", "locationId", "manufacturer", "model", "profile", "protocol", "sourceId",
                "preset", "startImmediately"));
        ALLOWED_PARAMETERS.put(AgentTool.START_SIMULATION, Set.of("deviceId"));
        ALLOWED_PARAMETERS.put(AgentTool.STOP_SIMULATION, Set.of("deviceId"));
    }

    public AgentPlan sanitize(AuthenticatedUser user, String message, LlmPlanDraft draft, AgentPlan fallback) {
        List<AgentAction> actions = new ArrayList<>();
        for (LlmActionDraft actionDraft : safeActions(draft)) {
            AgentTool tool = parseTool(actionDraft.tool());
            if (tool == null) {
                com.voltcanary.aiagent.trace.AssistantTrace.event("SANITIZATION", "FAILED", "UNSUPPORTED_TOOL", Map.of("tool", String.valueOf(actionDraft.tool())));
                continue;
            }
            if ((tool == AgentTool.CREATE_DEVICE || tool == AgentTool.CREATE_DASHBOARD) && actionDraft.parameters() != null
                    && !ALLOWED_PARAMETERS.get(tool).containsAll(actionDraft.parameters().keySet())) {
                com.voltcanary.aiagent.trace.AssistantTrace.event("SANITIZATION", "FAILED", "UNSUPPORTED_FIELDS",
                        Map.of("tool", tool.name(), "fieldPaths", actionDraft.parameters().keySet().stream()
                                .filter(field -> !ALLOWED_PARAMETERS.get(tool).contains(field)).sorted().toList(), "candidate", actionDraft.parameters()));
                return new AgentPlan(UUID.randomUUID(), user.userId(), message, "Не удалось подготовить изменения",
                        "Модель предложила несовместимые параметры. Устройство и другие изменения не создавались. Можно повторить запрос; уже указанные имя и тип менять не нужно.",
                        List.of("MODEL_CONTRACT_REJECTED"), List.of(), Instant.now());
            }
            Map<String, Object> parameters = mergedParameters(tool, actionDraft.parameters(), fallback);
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    tool,
                    textOrFallback(actionDraft.title(), fallbackTitle(tool)),
                    textOrFallback(actionDraft.description(), fallbackDescription(tool)),
                    requiresConfirmation(tool),
                    parameters));
        }

        com.voltcanary.aiagent.trace.AssistantTrace.event("SANITIZATION", "SUCCEEDED", null, Map.of("actionCount", actions.size()));
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
                Instant.now(),"rules","Rules","rule-based planner",null,null,true,
                actions.isEmpty() && draft.clarification()!=null && draft.clarification().bounded()?draft.clarification():null);
    }

    private List<LlmActionDraft> safeActions(LlmPlanDraft draft) {
        return draft.actions() == null ? List.of() : draft.actions();
    }

    private AgentTool parseTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return null;
        }
        try {
            AgentTool selected = AgentTool.valueOf(tool.trim().toUpperCase(Locale.ROOT));
            return selected == AgentTool.UPDATE_DASHBOARD_DRAFT || selected == AgentTool.CREATE_NATIVE_TABLE
                    || selected == AgentTool.APPLY_SOURCE_MAPPING || selected == AgentTool.UPDATE_VEHICLE_MONITORING ? null : selected;
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
        if (tool == AgentTool.CREATE_DEVICE || tool == AgentTool.CREATE_DASHBOARD) return new LinkedHashMap<>();
        return fallback.actions().stream()
                .filter(action -> action.tool() == tool)
                .findFirst()
                .map(AgentAction::parameters)
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
    }

    private String fallbackTitle(AgentTool tool) {
        return switch (tool) {
            case UPDATE_VEHICLE_MONITORING -> "Review vehicle monitoring";
            case CREATE_NATIVE_TABLE -> "Create reviewed native table";
            case APPLY_SOURCE_MAPPING -> "Apply reviewed source mapping";
            case CREATE_DEVICE -> "Create device";
            case CREATE_DASHBOARD -> "Create private dashboard draft";
            case UPDATE_DASHBOARD_DRAFT -> "Update reviewed dashboard draft";
            case CREATE_DATA_SOURCE -> "Create simulator data source";
            case CREATE_SIMULATOR_CONFIG -> "Create simulator config";
            case VALIDATE_SIMULATOR_YAML -> "Validate simulator YAML";
            case CREATE_ALERT_RULE -> "Create alert rule";
            case UPDATE_ALERT_RULE -> "Update alert rule";
            case TEST_ALERT_RULE -> "Test alert rule";
            case EXPLAIN_ALERT -> "Explain alert";
            case OPEN_TELEMETRY_WINDOW -> "Open telemetry window";
            case CREATE_DEMO_DEVICE -> "Create demo device";
            case START_SIMULATION -> "Start simulation";
            case STOP_SIMULATION -> "Stop simulation";
        };
    }

    private String fallbackDescription(AgentTool tool) {
        return switch (tool) {
            case UPDATE_VEHICLE_MONITORING -> "Change monitoring through the reviewed telemetry owner.";
            case CREATE_NATIVE_TABLE -> "Create the exact owner-reviewed native collection.";
            case APPLY_SOURCE_MAPPING -> "Apply the exact owner-reviewed source mapping.";
            case CREATE_DEVICE -> "Create a device through device-service using the current user's authorization.";
            case CREATE_DASHBOARD -> "Prepare an existing template and create a private draft through dashboard-service.";
            case UPDATE_DASHBOARD_DRAFT -> "Update the same dashboard draft with its reviewed version through dashboard-service.";
            case CREATE_DATA_SOURCE -> "Attach a SIMULATOR data source to the target device through device-service.";
            case CREATE_SIMULATOR_CONFIG -> "Create simulator YAML through simulator-service.";
            case VALIDATE_SIMULATOR_YAML -> "Validate simulator YAML without changing resources.";
            case CREATE_ALERT_RULE -> "Create an alert rule through alert-service.";
            case UPDATE_ALERT_RULE -> "Update an existing alert rule through alert-service.";
            case TEST_ALERT_RULE -> "Test an alert rule against recent telemetry without saving it.";
            case EXPLAIN_ALERT -> "Load alert and rule details and return an operator-focused explanation.";
            case OPEN_TELEMETRY_WINDOW -> "Return a route to the relevant device telemetry window.";
            case CREATE_DEMO_DEVICE -> "Create a demo device, simulator source, and simulator configuration.";
            case START_SIMULATION -> "Start simulator telemetry for the target device.";
            case STOP_SIMULATION -> "Stop simulator telemetry for the target device.";
        };
    }

    private boolean requiresConfirmation(AgentTool tool) {
        return switch (tool) {
            case VALIDATE_SIMULATOR_YAML, TEST_ALERT_RULE, EXPLAIN_ALERT, OPEN_TELEMETRY_WINDOW -> false;
            default -> true;
        };
    }

    private String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
