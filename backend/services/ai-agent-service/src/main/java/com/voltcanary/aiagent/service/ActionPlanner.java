package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.llm.LlmPlanProvider;
import com.voltcanary.aiagent.llm.LlmPlanSanitizer;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.llm.adapter.AiModelAdapterRegistry;
import com.voltcanary.aiagent.llm.adapter.AiModelPlanRequest;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ActionPlanner {
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

    private final List<LlmPlanProvider> llmPlanProviders;
    private final LlmPlanSanitizer llmPlanSanitizer;
    private final AiModelResolver aiModelResolver;
    private final AiModelAdapterRegistry adapterRegistry;
    private final AiAgentModelRunRecorder runRecorder;

    public ActionPlanner(
            List<LlmPlanProvider> llmPlanProviders,
            LlmPlanSanitizer llmPlanSanitizer,
            AiModelResolver aiModelResolver,
            AiModelAdapterRegistry adapterRegistry,
            AiAgentModelRunRecorder runRecorder) {
        this.llmPlanProviders = llmPlanProviders;
        this.llmPlanSanitizer = llmPlanSanitizer;
        this.aiModelResolver = aiModelResolver;
        this.adapterRegistry = adapterRegistry;
        this.runRecorder = runRecorder;
    }

    public AgentPlan plan(AuthenticatedUser user, String message, Map<String, Object> context, UUID providerId, UUID modelConfigId) {
        AgentPlan fallback = ruleBasedPlan(user, message, context);
        var resolvedModel = aiModelResolver.resolve(user, providerId, modelConfigId);
        if (resolvedModel.isPresent()) {
            AiResolvedModel model = resolvedModel.get();
            long started = System.currentTimeMillis();
            try {
                LlmPlanDraft draft = adapterRegistry.adapterFor(model.provider().getProviderType())
                        .plan(new AiModelPlanRequest(user.accountId(), user.userId(), message, context, SYSTEM_PROMPT, model));
                runRecorder.record(user.accountId(), user.userId(), model, "SUCCESS", System.currentTimeMillis() - started, null, null);
                AgentPlan plan = llmPlanSanitizer.sanitize(user, message, draft, fallback);
                return withModelMetadata(plan, model, false);
            } catch (Exception exception) {
                runRecorder.record(user.accountId(), user.userId(), model, "FAILED", System.currentTimeMillis() - started, "PROVIDER_FAILED", exception.getMessage());
            }
        }
        for (LlmPlanProvider provider : llmPlanProviders) {
            var draft = provider.plan(message, context);
            if (draft.isPresent()) {
                LlmPlanDraft llmPlanDraft = draft.get();
                return llmPlanSanitizer.sanitize(user, message, llmPlanDraft, fallback);
            }
        }
        return fallback;
    }

    public AgentPlan plan(AuthenticatedUser user, String message, Map<String, Object> context) {
        return plan(user, message, context, null, null);
    }

    private AgentPlan withModelMetadata(AgentPlan plan, AiResolvedModel model, boolean fallbackUsed) {
        return new AgentPlan(
                plan.id(),
                plan.userId(),
                plan.userMessage(),
                plan.summary(),
                plan.explanation(),
                plan.safetyNotes(),
                plan.actions(),
                plan.createdAt(),
                model.provider().getProviderType().name(),
                model.provider().getName(),
                model.modelConfig().getModelId(),
                model.provider().getId(),
                model.modelConfig().getId(),
                fallbackUsed);
    }

    private AgentPlan ruleBasedPlan(AuthenticatedUser user, String message, Map<String, Object> context) {
        String normalized = message.toLowerCase(Locale.ROOT);
        List<AgentAction> actions = new ArrayList<>();
        boolean wantsDevice = containsAny(normalized, "device", "устрой", "датчик", "розет", "fridge", "холодиль", "meter");
        boolean wantsSimulator = containsAny(normalized, "simulator", "симуля", "yaml", "source", "источник");

        if (wantsDevice) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DEVICE,
                    "Create device",
                    "Create a device through device-service using the current user's authorization.",
                    true,
                    deviceParameters(message, context)));
        }

        if (wantsSimulator || wantsDevice) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DATA_SOURCE,
                    "Create simulator data source",
                    "Attach a SIMULATOR data source to the target device through device-service.",
                    true,
                    dataSourceParameters(message, context)));
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_SIMULATOR_CONFIG,
                    "Create simulator config",
                    "Create or replace simulator YAML through simulator-service.",
                    true,
                    simulatorParameters(message, context)));
        }

        if (actions.isEmpty()) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.VALIDATE_SIMULATOR_YAML,
                    "Validate simulator YAML",
                    "Validate provided simulator YAML without changing resources.",
                    false,
                    Map.of("yamlConfig", stringValue(context, "yamlConfig", ""))));
        }

        return new AgentPlan(
                UUID.randomUUID(),
                user.userId(),
                message,
                "Planned " + actions.size() + " action(s)",
                "I will use existing service APIs only, forward the user's token, and ask for confirmation before writes.",
                List.of(
                        "No direct access to device, telemetry, or analytics tables.",
                        "No accountId override. Ownership is enforced by downstream services from the user's token.",
                        "No global admin token is used.",
                        "Every plan and action execution is written to the ai-agent audit log."),
                actions,
                Instant.now());
    }

    private Map<String, Object> deviceParameters(String message, Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("name", stringValue(context, "name", inferName(message)));
        parameters.put("type", stringValue(context, "type", inferType(message)));
        parameters.put("locationId", context == null ? null : context.get("locationId"));
        parameters.put("manufacturer", stringValue(context, "manufacturer", "VoltCanary"));
        parameters.put("model", stringValue(context, "model", "AI-created"));
        return parameters;
    }

    private Map<String, Object> dataSourceParameters(String message, Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("deviceId", context == null ? null : context.get("deviceId"));
        parameters.put("sourceType", "SIMULATOR");
        parameters.put("protocol", containsAny(message.toLowerCase(Locale.ROOT), "mqtt") ? "MQTT" : "REST");
        parameters.put("externalId", stringValue(context, "sourceId", "sim-" + UUID.randomUUID().toString().substring(0, 8)));
        return parameters;
    }

    private Map<String, Object> simulatorParameters(String message, Map<String, Object> context) {
        String sourceId = stringValue(context, "sourceId", "sim-" + UUID.randomUUID().toString().substring(0, 8));
        String profile = stringValue(context, "profile", inferProfile(message));
        String protocol = containsAny(message.toLowerCase(Locale.ROOT), "mqtt") ? "MQTT" : "HTTP";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("deviceId", context == null ? null : context.get("deviceId"));
        parameters.put("dataSourceId", context == null ? null : context.get("dataSourceId"));
        parameters.put("name", stringValue(context, "simulatorName", sourceId + "-simulator"));
        parameters.put("profile", profile);
        parameters.put("protocol", protocol);
        parameters.put("sourceId", sourceId);
        parameters.put("yamlConfig", stringValue(context, "yamlConfig", defaultYaml(sourceId, profile, protocol)));
        return parameters;
    }

    private String defaultYaml(String sourceId, String profile, String protocol) {
        return """
                schemaVersion: 1
                simulator:
                  name: %s-simulator
                  profile: %s
                  mode: NORMAL
                  protocol: %s
                target:
                  sourceId: "%s"
                schedule:
                  interval: 5s
                  startDelay: 0s
                  duration: 30m
                  speed: 1.0
                metrics:
                  voltageV:
                    type: constant
                    value: 230.0
                  rssi:
                    type: constant
                    value: -61
                  activePowerW:
                    type: cycle
                    offValue:
                      mean: 1.4
                    onValue:
                      mean: 120.0
                      noise: 8.0
                    cycle:
                      onDuration:
                        min: 8m
                        max: 16m
                      offDuration:
                        min: 20m
                        max: 45m
                  currentA:
                    type: derived
                    expression: "activePowerW / voltageV"
                  energyWh:
                    type: accumulator
                    from: activePowerW
                relayState:
                  type: derived
                  expression: "activePowerW > 5 ? 'ON' : 'OFF'"
                anomalies: []
                """.formatted(sourceId, profile, protocol, sourceId);
    }

    private String inferName(String message) {
        if (message.toLowerCase(Locale.ROOT).contains("fridge") || message.toLowerCase(Locale.ROOT).contains("холодиль")) {
            return "AI Fridge";
        }
        return "AI Created Device";
    }

    private String inferType(String message) {
        if (message.toLowerCase(Locale.ROOT).contains("fridge") || message.toLowerCase(Locale.ROOT).contains("холодиль")) {
            return "FRIDGE";
        }
        return "SMART_PLUG";
    }

    private String inferProfile(String message) {
        return inferType(message).equals("FRIDGE") ? "FRIDGE" : "GENERIC_APPLIANCE";
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Map<String, Object> context, String key, String fallback) {
        Object value = context == null ? null : context.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
