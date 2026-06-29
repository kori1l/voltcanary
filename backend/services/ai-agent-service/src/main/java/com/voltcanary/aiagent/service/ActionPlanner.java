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
            - CREATE_ALERT_RULE
            - UPDATE_ALERT_RULE
            - TEST_ALERT_RULE
            - EXPLAIN_ALERT
            - OPEN_TELEMETRY_WINDOW
            - CREATE_DEMO_DEVICE
            - START_SIMULATION
            - STOP_SIMULATION
            Never include accountId, tenantId, admin credentials, direct database access, or telemetry table access.
            Read-only tools are VALIDATE_SIMULATOR_YAML, TEST_ALERT_RULE, EXPLAIN_ALERT, and OPEN_TELEMETRY_WINDOW.
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
        boolean wantsDemoDevice = containsAny(normalized, "demo", "sample", "simulated")
                && containsAny(normalized, "device", "plug", "fridge", "meter");
        boolean wantsDevice = containsAny(normalized, "device", "sensor", "plug", "fridge", "meter");
        boolean wantsSimulator = containsAny(normalized, "simulator", "simulation", "yaml", "source", "telemetry source");
        boolean wantsRule = containsAny(normalized, "alert rule", "rule", "threshold", "power spike", "configure alert");
        boolean wantsRuleTest = containsAny(normalized, "test rule", "simulate rule", "preview rule");
        boolean wantsExplainAlert = containsAny(normalized, "explain alert", "why alert", "alert explanation")
                || (contextValue(context, "alertId") != null && containsAny(normalized, "explain", "why"));
        boolean wantsTelemetry = containsAny(normalized, "open telemetry", "telemetry window", "show telemetry")
                || (contextValue(context, "deviceId") != null && containsAny(normalized, "telemetry", "chart", "window"));
        boolean wantsStart = containsAny(normalized, "start simulation", "start simulator");
        boolean wantsStop = containsAny(normalized, "stop simulation", "stop simulator");

        if (wantsExplainAlert) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.EXPLAIN_ALERT,
                    "Explain alert",
                    "Load alert details, related rule data when available, and the telemetry route for evidence.",
                    false,
                    Map.of("alertId", stringValue(context, "alertId", ""))));
        }

        if (wantsTelemetry) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.OPEN_TELEMETRY_WINDOW,
                    "Open telemetry window",
                    "Open the device telemetry view with the current metric and time window.",
                    false,
                    telemetryParameters(context)));
        }

        if (wantsRuleTest) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.TEST_ALERT_RULE,
                    "Test alert rule",
                    "Preview an alert rule against the selected device and time window without saving it.",
                    false,
                    alertRuleParameters(context)));
        } else if (wantsRule) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_ALERT_RULE,
                    "Create alert rule",
                    "Create a configurable alert rule using alert-service after confirmation.",
                    true,
                    alertRuleParameters(context)));
        }

        if (wantsDemoDevice) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DEMO_DEVICE,
                    "Create demo device",
                    "Create a demo device with a simulator source and simulator configuration.",
                    true,
                    demoDeviceParameters(message, context)));
        } else if (wantsDevice) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DEVICE,
                    "Create device",
                    "Create a device through device-service using the current user's authorization.",
                    true,
                    deviceParameters(message, context)));
        }

        if (!wantsDemoDevice && (wantsSimulator || wantsDevice)) {
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

        if (wantsStart) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.START_SIMULATION,
                    "Start simulation",
                    "Start simulator telemetry for the target device.",
                    true,
                    Map.of("deviceId", stringValue(context, "deviceId", ""))));
        }

        if (wantsStop) {
            actions.add(new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.STOP_SIMULATION,
                    "Stop simulation",
                    "Stop simulator telemetry for the target device.",
                    true,
                    Map.of("deviceId", stringValue(context, "deviceId", ""))));
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
        parameters.put("locationId", contextValue(context, "locationId"));
        parameters.put("manufacturer", stringValue(context, "manufacturer", "VoltCanary"));
        parameters.put("model", stringValue(context, "model", "AI-created"));
        return parameters;
    }

    private Map<String, Object> dataSourceParameters(String message, Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("deviceId", contextValue(context, "deviceId"));
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
        parameters.put("deviceId", contextValue(context, "deviceId"));
        parameters.put("dataSourceId", contextValue(context, "dataSourceId"));
        parameters.put("name", stringValue(context, "simulatorName", sourceId + "-simulator"));
        parameters.put("profile", profile);
        parameters.put("protocol", protocol);
        parameters.put("sourceId", sourceId);
        parameters.put("yamlConfig", stringValue(context, "yamlConfig", defaultYaml(sourceId, profile, protocol)));
        return parameters;
    }

    private Map<String, Object> demoDeviceParameters(String message, Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>(deviceParameters(message, context));
        String sourceId = stringValue(context, "sourceId", "sim-" + UUID.randomUUID().toString().substring(0, 8));
        parameters.put("profile", stringValue(context, "profile", inferProfile(message)));
        parameters.put("protocol", stringValue(context, "protocol", "HTTP"));
        parameters.put("sourceId", sourceId);
        parameters.put("preset", stringValue(context, "preset", "NORMAL_OPERATION"));
        parameters.put("startImmediately", contextValue(context, "startImmediately") == null ? Boolean.FALSE : contextValue(context, "startImmediately"));
        return parameters;
    }

    private Map<String, Object> alertRuleParameters(Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("deviceId", contextValue(context, "deviceId"));
        parameters.put("from", contextValue(context, "from"));
        parameters.put("to", contextValue(context, "to"));
        parameters.put("name", stringValue(context, "ruleName", "AI suggested alert rule"));
        parameters.put("description", stringValue(context, "description", "Created from assistant context."));
        parameters.put("ruleType", stringValue(context, "ruleType", "THRESHOLD"));
        parameters.put("enabled", Boolean.TRUE);
        parameters.put("scopeType", contextValue(context, "deviceId") == null ? "FLEET" : "DEVICE");
        parameters.put("scopeId", contextValue(context, "deviceId"));
        parameters.put("metric", stringValue(context, "metric", "activePowerW"));
        parameters.put("conditionType", stringValue(context, "conditionType", "ABOVE_THRESHOLD"));
        parameters.put("threshold", contextValue(context, "threshold") == null ? 100 : contextValue(context, "threshold"));
        parameters.put("thresholdUnit", stringValue(context, "thresholdUnit", "W"));
        parameters.put("baselineMultiplier", contextValue(context, "baselineMultiplier"));
        parameters.put("minPoints", contextValue(context, "minPoints") == null ? 1 : contextValue(context, "minPoints"));
        parameters.put("severity", stringValue(context, "severity", "WARNING"));
        parameters.put("cooldownSeconds", contextValue(context, "cooldownSeconds") == null ? 900 : contextValue(context, "cooldownSeconds"));
        parameters.put("deduplicationWindowSeconds", contextValue(context, "deduplicationWindowSeconds") == null ? 900 : contextValue(context, "deduplicationWindowSeconds"));
        parameters.put("config", Map.of());
        return parameters;
    }

    private Map<String, Object> telemetryParameters(Map<String, Object> context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("deviceId", contextValue(context, "deviceId"));
        parameters.put("metric", stringValue(context, "metric", "activePowerW"));
        parameters.put("from", contextValue(context, "from"));
        parameters.put("to", contextValue(context, "to"));
        parameters.put("alertId", contextValue(context, "alertId"));
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
        if (message.toLowerCase(Locale.ROOT).contains("fridge")) {
            return "AI Fridge";
        }
        return "AI Created Device";
    }

    private String inferType(String message) {
        if (message.toLowerCase(Locale.ROOT).contains("fridge")) {
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
        Object value = contextValue(context, key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private Object contextValue(Map<String, Object> context, String key) {
        return context == null ? null : context.get(key);
    }
}
