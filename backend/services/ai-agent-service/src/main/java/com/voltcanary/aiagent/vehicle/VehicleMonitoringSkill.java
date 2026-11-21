package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VehicleMonitoringSkill {
    public static final String INTENT = "UPDATE_VEHICLE_MONITORING";
    private VehicleMonitoringSkill() { }
    public static JsonNode schema(ObjectMapper mapper) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("op", Map.of("type", "string", "enum", List.of("ADD", "UPDATE", "REMOVE")));
        fields.put("zoneId", nullable(Map.of("type", "string", "format", "uuid")));
        fields.put("name", nullable(Map.of("type", "string", "minLength", 1, "maxLength", 100)));
        fields.put("latitude", nullable(Map.of("type", "number", "minimum", -90, "maximum", 90)));
        fields.put("longitude", nullable(Map.of("type", "number", "minimum", -180, "maximum", 180)));
        fields.put("radiusMetres", nullable(Map.of("type", "number", "minimum", 10, "maximum", 50000)));
        return mapper.valueToTree(object(Map.of("deviceId", Map.of("type", "string", "format", "uuid"),
                "expectedRevision", Map.of("type", "integer", "minimum", 0),
                "sourceId", nullable(Map.of("type", "string", "format", "uuid")),
                "speedLimitKmh", nullable(Map.of("type", "number", "minimum", 1, "maximum", 300)),
                "idleMinutes", nullable(Map.of("type", "integer", "minimum", 1, "maximum", 60)),
                "zoneEdits", Map.of("type", "array", "maxItems", 50, "items", object(fields)))));
    }
    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> nullable(Map<String, Object> schema) { return Map.of("anyOf", List.of(schema, Map.of("type", "null"))); }
    public static SkillRegistry.Package create(ObjectMapper mapper, ProtocolValidator validator) {
        JsonNode command = schema(mapper);
        var input = mapper.valueToTree(Map.of("type", "object", "properties", Map.of("deviceId", Map.of("type", "string", "format", "uuid")), "additionalProperties", false));
        var output = validator.projection("stepResponse");
        var envelope = (ObjectNode) output.at("/$defs/command/properties");
        envelope.set("schemaId", mapper.valueToTree(Map.of("const", INTENT + ":command:1")));
        envelope.set("schemaVersion", mapper.valueToTree(Map.of("const", "1")));
        envelope.set("payload", command);
        String instructions = "Configure the existing Vehicles console, never create a dashboard. Read workflow.context for the authorized vehicle, current revision, settings and GPS sources. "
                + "Use the exact selected/resolved deviceId and expectedRevision. sourceId is a registered source UUID from context, not externalId. Preserve the selected source; if several exist and none is selected, ask. With no GPS source, sourceId=null is allowed for zone/threshold configuration. "
                + "Interpret the current message and dialogue. Null threshold or zone field means unchanged. Empty zoneEdits preserves ALL zones. ADD needs name, explicit latitude/longitude and radiusMetres, zoneId=null; the server assigns the ID. "
                + "UPDATE/REMOVE use exact existing zoneId; ask if zone names are ambiguous. REMOVE requires all other fields null. Do not remove omitted zones. "
                + "context.selectedPoint is an explicit user-selected map centre and can be used for the requested zone. Never invent coordinates from a place name, vehicle readings, alerts or approvals. Ask the user to choose a point in Vehicles or supply coordinates. "
                + "Missing identity uses QUESTION with missingFields deviceId; missing geometry uses zoneEdits. These settings affect existing history views, not durable background alerts or notifications. PREPARED is review only.";
        var rules = List.of(new AssistantProtocol.ManifestParameterRulesItem("deviceId", "device-id", true, false, "deviceId", null, List.of(), "USER_ONLY"),
                new AssistantProtocol.ManifestParameterRulesItem("zoneEdits", "array", false, false, "zoneEdits", null, List.of(), "MODEL_MAY_PROPOSE"),
                new AssistantProtocol.ManifestParameterRulesItem("sourceId", "source-id", false, true, "sourceId", null, List.of(), "USER_ONLY"));
        var manifest = new AssistantProtocol.Manifest("assistant-v2", "skill.manifest", "vehicle-monitoring", "1", INTENT,
                "Change zones, speed limit or trip idle threshold for an existing vehicle in Vehicles, with shared manual/AI review and confirmation. No new vehicle/dashboard, GPS connection, rule activation or notifications.",
                List.of("assistant-v2"), INTENT + ":input:1", INTENT + ":output:1", INTENT + ":command:1", rules,
                List.of(), "MODEL", "REVIEW_REQUIRED", List.of("workflow.context", "conversation.history"), List.of(INTENT),
                List.of("SCOPED_DOMAIN_PREPARATION", "IMMUTABLE_REVIEW"), "primary", 100_000L,
                new AssistantProtocol.ManifestInstructions(instructions, SkillRegistry.digest(instructions)));
        return new SkillRegistry.Package(mapper.valueToTree(manifest), Map.of(INTENT + ":input:1", input, INTENT + ":command:1", command, INTENT + ":output:1", output,
                "workflow.context:args:1", mapper.valueToTree(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
                "conversation.history:args:1", mapper.valueToTree(Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 12)), "required", List.of("limit"), "additionalProperties", false))),
                Set.of(), Map.of("workflow.context", "workflow.context:args:1", "conversation.history", "conversation.history:args:1"), Set.of("deviceId"));
    }
}
