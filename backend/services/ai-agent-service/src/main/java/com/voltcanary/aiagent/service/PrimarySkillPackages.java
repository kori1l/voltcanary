package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deployment-owned tool contracts. Only the model interprets names, periods, types and replies. */
public final class PrimarySkillPackages {
    private PrimarySkillPackages() { }
    public static List<SkillRegistry.Package> build(ObjectMapper mapper, ProtocolValidator validator, List<ScopedDomainContextLoaders.Definition> domain) {
        var packages = new ArrayList<SkillRegistry.Package>();
        var intents = new ArrayList<>(java.util.Arrays.stream(AgentTool.values()).map(Enum::name).toList());
        intents.add(DashboardSkillContract.REFINE);
        for (String id : intents) {
            if (id.equals(com.voltcanary.aiagent.nativeops.NativeTableSkill.INTENT)) {
                packages.add(com.voltcanary.aiagent.nativeops.NativeTableSkill.create(mapper, validator));
                continue;
            }
            var tool = id.equals(DashboardSkillContract.REFINE) ? AgentTool.CREATE_DASHBOARD : AgentTool.valueOf(id);
            boolean dashboard = DashboardSkillContract.supports(id);
            String version = dashboard ? DashboardSkillContract.VERSION : "1";
            var fields = fields(tool);
            if (dashboard && !id.equals(DashboardSkillContract.CREATE)) fields.put("widgetKeys", "array");
            var input = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
            var properties = input.putObject("properties");
            var rules = new ArrayList<AssistantProtocol.ManifestParameterRulesItem>();
            fields.forEach((field, type) -> {
                var schema = properties.putObject(field).put("type", type.endsWith("-id") ? "string" : type);
                if (field.endsWith("Id")) schema.put("format", "uuid");
                if (type.equals("array")) schema.putObject("items").put("type", "string");
                if (field.equals("period")) schema.putArray("enum").add("LAST_1_HOUR").add("LAST_6_HOURS").add("LAST_24_HOURS").add("LAST_7_DAYS").add("LAST_30_DAYS");
                if (field.equals("name")) schema.put("maxLength", (tool == AgentTool.CREATE_DASHBOARD || tool == AgentTool.UPDATE_DASHBOARD_DRAFT) ? 80 : 255);
                boolean mandatory = required(tool).contains(field);
                String policy = tool == AgentTool.CREATE_DEVICE && field.equals("type") ? "USER_ONLY" : "MODEL_MAY_PROPOSE";
                rules.add(new AssistantProtocol.ManifestParameterRulesItem(field, type, mandatory, false, field, type.equals("string") ? 8000L : null, List.of(), policy));
            });
            JsonNode command = input.deepCopy();
            if (dashboard) command = DashboardSkillContract.schema(mapper, id);
            else {
                var required = ((com.fasterxml.jackson.databind.node.ObjectNode) command).putArray("required");
                required(tool).forEach(required::add);
                if (tool == AgentTool.CREATE_DATA_SOURCE) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) command.path("properties").path("provider")).putArray("enum").add("HSL_HFP");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) command.path("properties").path("mode")).putArray("enum").add("bus").add("tram");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) command.path("properties").path("vehicleKey")).put("pattern", "^[0-9]{4}-[0-9]{5}$");
                }
            }
            String output = switch (tool) { case CREATE_DEVICE, CREATE_DEMO_DEVICE -> "deviceId"; case CREATE_DASHBOARD, UPDATE_DASHBOARD_DRAFT -> "dashboardId"; case CREATE_DATA_SOURCE -> "dataSourceId"; case CREATE_ALERT_RULE -> "ruleId"; default -> null; };
            String outputType = output == null ? null : output.equals("deviceId") ? "device-id" : output.equals("dashboardId") ? "dashboard-id" : output.equals("dataSourceId") ? "source-id" : "rule-id";
            String instructions = dashboard ? DashboardSkillGuide.render(mapper, id) : PrimarySkillGuide.render(tool, description(tool),
                    "Prepare " + id + " only through command schema " + id + ":command:" + version + ". "
                    + "command.schemaVersion='" + version + "'. Only registered parameters, no execution claims. "
                    + "Use the exact current message, mandatory history/question/options and confirmed facts. "
                    + "Optional fields remain optional; propose a suitable name rather than asking for optional metadata. "
                    + "USER_ONLY fields must be resolved from the current message, prior user answers or confirmed facts; catalogue membership alone is not a user choice. Ask when their meaning remains missing. "
                    + "QUESTION.missingFields uses declared parameterRules only; missing domain data uses a registered loader. "
                    + "Resource labels/UUIDs are not permission; never invent observations or integrations.");
            var loaderSchemas = new LinkedHashMap<String, String>();
            var schemas = new LinkedHashMap<String, JsonNode>();
            schemas.put(id + ":input:" + version, input);
            schemas.put(id + ":command:" + version, command);
            var outputSchema = validator.projection("stepResponse");
            var commandProperties = (com.fasterxml.jackson.databind.node.ObjectNode) outputSchema.at("/$defs/command/properties");
            commandProperties.set("schemaId", mapper.valueToTree(Map.of("const", id + ":command:" + version)));
            commandProperties.set("schemaVersion", mapper.valueToTree(Map.of("const", version)));
            commandProperties.set("payload", command.deepCopy());
            schemas.put(id + ":output:" + version, outputSchema);
            domain.stream().filter(def -> !dashboard || DashboardSkillContract.loaderIds().contains(def.id())).forEach(def -> {
                loaderSchemas.put(def.id(), def.argumentsSchemaId());
                schemas.put(def.argumentsSchemaId(), def.argumentsSchema());
            });
            loaderSchemas.put("workflow.context", "workflow.context:args:1");
            schemas.put("workflow.context:args:1", mapper.valueToTree(Map.of("type", "object", "properties", Map.of("deviceIds", Map.of("type", "array", "maxItems", 20,
                    "items", Map.of("type", "string", "format", "uuid"))), "additionalProperties", false)));
            loaderSchemas.put("conversation.history", "conversation.history:args:1");
            schemas.put("conversation.history:args:1", mapper.valueToTree(Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 12)), "required", List.of("limit"), "additionalProperties", false)));
            var manifest = new AssistantProtocol.Manifest("assistant-v2", "skill.manifest", id.toLowerCase(java.util.Locale.ROOT), version, id, workflowDescription(id, tool, command),
                    List.of("assistant-v2"), id + ":input:" + version, id + ":output:" + version, id + ":command:" + version, rules,
                    output == null ? List.of() : List.of(new AssistantProtocol.ManifestOutputsItem(output, outputType)), "MODEL", "REVIEW_REQUIRED",
                    loaderSchemas.keySet().stream().sorted().toList(), List.of(id), List.of("SCOPED_DOMAIN_PREPARATION", "IMMUTABLE_REVIEW"), "primary", 200_000L,
                    new AssistantProtocol.ManifestInstructions(instructions, SkillRegistry.digest(instructions)));
            packages.add(new SkillRegistry.Package(mapper.valueToTree(manifest), schemas, Set.of(), loaderSchemas,
                    fields.entrySet().stream().filter(field -> field.getValue().endsWith("-id") || field.getKey().equals("deviceIds"))
                            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())));
        }
        return packages;
    }
    private static String workflowDescription(String intent, AgentTool tool, JsonNode command) {
        if (!DashboardSkillContract.supports(intent)) return description(tool);
        var operations = new ArrayList<String>();
        for (var variant : command.at("/properties/operations/items/anyOf")) {
            operations.add(variant.at("/properties/op/enum/0").asText());
        }
        return DashboardSkillGuide.purpose(intent) + " Supported skill operations: " + String.join(", ", operations)
                + ". inputKeys limits resource bindings only, not the supported user changes. Pass domain details through the exact message/history to this skill.";
    }
    private static String description(AgentTool tool) {
        if (tool == AgentTool.UPDATE_DASHBOARD_DRAFT) return "Change an actual already-created dashboard draft on the same dashboardId. Requires an executed reviewed base and fresh current draft/revision. Never substitute CREATE_DASHBOARD or a copy. Use this for edits after creation, not refinement of an unexecuted review.";
        return switch (tool) {
            case CREATE_NATIVE_TABLE -> "Create a new native data table through reviewed owner schema creation.";
            case CREATE_DASHBOARD, UPDATE_DASHBOARD_DRAFT -> "Create a dashboard for selected existing devices or confirmed created devices; refine its unexecuted review, widgets, names, layout, settings or undo. This step reads actual telemetry/source capabilities itself. A dashboard does not require a CREATE_DEVICE or CREATE_DATA_SOURCE step for an existing selected device.";
            case CREATE_DEVICE -> "Create a NEW device record, with catalogue type and optional location/manufacturer/model. Use only when the user requests a new device; existing selected devices already exist.";
            case CREATE_DATA_SOURCE -> "Connect the installed HSL_HFP source to an authorized existing/just-created VEHICLE using provider=HSL_HFP, mode=bus|tram and exact vehicleKey. Requires deviceSetupCapabilities=HSL_HFP. Never invent an arbitrary MQTT connection. This is a separate reviewed step/receipt; never add it just to visualize existing telemetry.";
            case CREATE_SIMULATOR_CONFIG -> "Prepare simulator configuration for a specified existing device and source.";
            case VALIDATE_SIMULATOR_YAML -> "Validate user-provided simulator YAML without starting a simulation.";
            case CREATE_ALERT_RULE -> "Create a NEW alert rule with metric, operator, threshold and optional severity/device scope.";
            case UPDATE_ALERT_RULE -> "Change a specified existing alert rule; retain its actual identity.";
            case TEST_ALERT_RULE -> "Test an alert rule against a telemetry interval without creating a new rule.";
            case EXPLAIN_ALERT -> "Explain a specified existing alert using authorized alert evidence.";
            case OPEN_TELEMETRY_WINDOW -> "Open a read-only telemetry window for a specified existing device, metric and interval.";
            case CREATE_DEMO_DEVICE -> "Create a NEW demonstration device, optional simulation setup, only when demonstration data is requested.";
            case START_SIMULATION -> "Start an existing device's configured simulation, only when requested.";
            case STOP_SIMULATION -> "Stop an existing device's simulation, only when requested.";
        };
    }
    private static List<String> required(AgentTool tool) {
        return switch (tool) {
            case CREATE_DEVICE, CREATE_DEMO_DEVICE -> List.of("name", "type");
            case CREATE_DATA_SOURCE -> List.of("deviceId", "provider", "mode", "vehicleKey");
            case CREATE_SIMULATOR_CONFIG -> List.of("deviceId", "dataSourceId");
            case START_SIMULATION, STOP_SIMULATION, OPEN_TELEMETRY_WINDOW -> List.of("deviceId");
            case VALIDATE_SIMULATOR_YAML -> List.of("yamlConfig");
            case EXPLAIN_ALERT -> List.of("alertId");
            case UPDATE_ALERT_RULE -> List.of("ruleId", "metric");
            case CREATE_ALERT_RULE, TEST_ALERT_RULE -> List.of("metric");
            default -> List.of();
        };
    }
    private static Map<String, String> fields(AgentTool tool) {
        String[] names = switch (tool) {
            case CREATE_NATIVE_TABLE -> new String[]{};
            case CREATE_DEVICE -> new String[]{"name", "type", "locationId", "manufacturer", "model"};
            case CREATE_DASHBOARD, UPDATE_DASHBOARD_DRAFT -> new String[]{"deviceId", "deviceIds", "sourceId", "name", "period"};
            case CREATE_DATA_SOURCE -> new String[]{"deviceId", "provider", "mode", "vehicleKey"};
            case CREATE_SIMULATOR_CONFIG, VALIDATE_SIMULATOR_YAML -> new String[]{"deviceId", "dataSourceId", "yamlConfig", "profile", "protocol", "sourceId"};
            case CREATE_ALERT_RULE, UPDATE_ALERT_RULE, TEST_ALERT_RULE -> new String[]{"ruleId", "deviceId", "name", "metric", "operator", "threshold", "severity", "enabled", "from", "to", "windowSeconds"};
            case EXPLAIN_ALERT -> new String[]{"alertId"};
            case OPEN_TELEMETRY_WINDOW -> new String[]{"deviceId", "metric", "from", "to"};
            case CREATE_DEMO_DEVICE -> new String[]{"name", "type", "profile", "protocol", "sourceId", "startImmediately"};
            case START_SIMULATION, STOP_SIMULATION -> new String[]{"deviceId"};
        };
        var result = new LinkedHashMap<String, String>();
        for (String name : names) result.put(name, name.equals("deviceId") ? "device-id" : name.equals("dataSourceId") || (tool == AgentTool.CREATE_DASHBOARD || tool == AgentTool.UPDATE_DASHBOARD_DRAFT) && name.equals("sourceId") ? "source-id" : name.equals("ruleId") ? "rule-id" : name.equals("alertId") ? "alert-id" : name.equals("locationId") ? "location-id" : Set.of("deviceIds", "widgetKeys").contains(name) ? "array" : Set.of("threshold", "windowSeconds").contains(name) ? "number"
                : Set.of("enabled", "startImmediately").contains(name) ? "boolean" : "string");
        return result;
    }
}
