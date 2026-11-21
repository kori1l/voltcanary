package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NativeMappingSkill {
    public static final String INTENT = "APPLY_SOURCE_MAPPING";
    static final String COMMAND = INTENT + ":command:1";
    private NativeMappingSkill() { }

    public static SkillRegistry.Package create(ObjectMapper mapper, ProtocolValidator validator) {
        var command = commandSchema(validator);
        var input = command.deepCopy();
        input.remove("required");
        var output = validator.projection("stepResponse");
        var properties = output.withObject("/$defs/command/properties");
        properties.putObject("schemaId").put("const", COMMAND);
        properties.putObject("schemaVersion").put("const", "1");
        properties.set("payload", command);
        String text = "Prepare a reviewed Boolean source mapping only for the explicitly selected source binding. "
                + "Use a field ID from workflow.context and exact user-provided tokens and sample. Ask for missing values. "
                + "The owner computes the preview. Never claim the mapping was applied.";
        var rules = List.of(
                new AssistantProtocol.ManifestParameterRulesItem("fieldId", "string", true, false, "Field", null, List.of(), "USER_ONLY"),
                new AssistantProtocol.ManifestParameterRulesItem("sample", "string", true, false, "Sample", 512L, List.of(), "USER_ONLY"),
                new AssistantProtocol.ManifestParameterRulesItem("conversion", "object", true, false, "Boolean tokens", null, List.of(), "USER_ONLY"));
        var manifest = new AssistantProtocol.Manifest("assistant-v2", "skill.manifest", "native-source-mapping", "1", INTENT,
                "Prepare a reviewed Boolean mapping for an explicitly selected VCL source binding.", List.of("assistant-v2"),
                INTENT + ":input:1", INTENT + ":output:1", COMMAND, rules, List.of(), "MODEL", "REVIEW_REQUIRED",
                List.of("workflow.context"), List.of(INTENT), List.of("SCOPED_DOMAIN_PREPARATION", "IMMUTABLE_REVIEW"),
                "primary", 100_000L, new AssistantProtocol.ManifestInstructions(text, SkillRegistry.digest(text)));
        Map<String, JsonNode> schemas = Map.of(INTENT + ":input:1", input, INTENT + ":output:1", output, COMMAND, command,
                "workflow.context:args:1", mapper.createObjectNode().put("type", "object").put("additionalProperties", false));
        return new SkillRegistry.Package(mapper.valueToTree(manifest), schemas, Set.of(NativeTableSkill.CAPABILITY),
                Map.of("workflow.context", "workflow.context:args:1"), Set.of());
    }

    static ObjectNode commandSchema(ProtocolValidator validator) {
        return (ObjectNode) validator.parse("""
                {"type":"object","additionalProperties":false,"required":["fieldId","sample","conversion"],
                 "properties":{"fieldId":{"type":"string","format":"uuid"},"sample":{"type":"string","minLength":1,"maxLength":512},
                 "conversion":{"type":"object","additionalProperties":false,"required":["method","trueTokens","falseTokens"],
                 "properties":{"method":{"const":"parse_boolean"},
                 "trueTokens":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string","minLength":1,"maxLength":256}},
                 "falseTokens":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string","minLength":1,"maxLength":256}}}}}}
                """);
    }
}
