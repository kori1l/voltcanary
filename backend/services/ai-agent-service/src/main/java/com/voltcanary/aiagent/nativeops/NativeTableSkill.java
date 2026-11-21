package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native authoring uses the existing inert proposal/model pipeline and ordinary owner control APIs. */
public final class NativeTableSkill {
    public static final String INTENT = "CREATE_NATIVE_TABLE";
    public static final String CAPABILITY = "NATIVE_COLLECTION_AUTHORING_1";
    public static final String VERSION = "1";
    public static final String PROTOCOL = "native-table-review-1";
    private NativeTableSkill() { }

    public static SkillRegistry.Package create(ObjectMapper mapper, ProtocolValidator validator) {
        var command = NativeCollectionDraft.schema(mapper);
        var input = command.deepCopy();
        input.remove("required");
        var output = validator.projection("stepResponse");
        var envelope = (ObjectNode) output.at("/$defs/command/properties");
        envelope.set("schemaId", mapper.valueToTree(Map.of("const", INTENT + ":command:1")));
        envelope.set("schemaVersion", mapper.valueToTree(Map.of("const", VERSION)));
        envelope.set("payload", command);
        String instructions = "Create one NEW native table using the ordinary /data reviewed owner protocol. "
                + "Interpret the exact current message and required dialogue/pending question; retain confirmed field meanings. "
                + "Propose only label, fields and history under this command schema. Symbolic field keys are technical ASCII tokens, not UUIDs. "
                + "The server reserves actual dataset/field/review/receipt IDs. Never invent IDs, SQL, credentials, URLs or approved flags. "
                + "This profile matches the manual native form: TEXT maxLength=120; DECIMAL precision=18 scale=3. "
                + "For ENUM provide choices, one exact value per line. For REF provide SELF, an authorized DATASET ID provided by the workflow, or a RESOURCE kind. "
                + "Numeric unit must be registered and grounded in user meaning; nonnumeric unit='1'. Business keys are nonnullable TEXT/INT64/DATE/INSTANT/ENUM/REF. POINT and INTERVAL cannot be indexed. "
                + "Ask a focused question only for missing field meaning/type/unit or unresolved requested policy, not optional metadata or technical owner failures. "
                + "History capture does not make historical queries available. This intent defines reference fields but does not link rows, edit existing tables, import data, edit maps or activate rules. "
                + "PREPARED is a proposal for explicit review, never a creation/execution claim. Use workflow.context for current owner capability and this exact operation schema. "
                + "Schema digest=" + SkillRegistry.digest(command.toString()) + "; VCL registry=" + new VclRegistry(new VclParser()).digest();
        var rules = List.of(
                new AssistantProtocol.ManifestParameterRulesItem("label", "string", true, false, "label", 80L, List.of(), "MODEL_MAY_PROPOSE"),
                new AssistantProtocol.ManifestParameterRulesItem("fields", "array", true, false, "fields", null, List.of(), "MODEL_MAY_PROPOSE"),
                new AssistantProtocol.ManifestParameterRulesItem("history", "boolean", true, false, "history", null, List.of(), "MODEL_MAY_PROPOSE"));
        var manifest = new AssistantProtocol.Manifest("assistant-v2", "skill.manifest", "native-table", VERSION, INTENT,
                "Create a NEW owned native data table with explicitly typed fields, optional business key/indexes and history capture. No rows, imports, existing table edits or executable RULEs.",
                List.of("assistant-v2"), INTENT + ":input:1", INTENT + ":output:1", INTENT + ":command:1", rules,
                List.of(new AssistantProtocol.ManifestOutputsItem("datasetId", "dataset-id")), "MODEL", "REVIEW_REQUIRED",
                List.of("workflow.context", "conversation.history"), List.of(INTENT), List.of("SCOPED_DOMAIN_PREPARATION", "IMMUTABLE_REVIEW"),
                "primary", 100_000L, new AssistantProtocol.ManifestInstructions(instructions, SkillRegistry.digest(instructions)));
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put(INTENT + ":input:1", input);
        schemas.put(INTENT + ":command:1", command);
        schemas.put(INTENT + ":output:1", output);
        schemas.put("workflow.context:args:1", mapper.valueToTree(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
        schemas.put("conversation.history:args:1", mapper.valueToTree(Map.of("type", "object", "properties",
                Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 12)), "required", List.of("limit"), "additionalProperties", false)));
        return new SkillRegistry.Package(mapper.valueToTree(manifest), schemas, Set.of(CAPABILITY),
                Map.of("workflow.context", "workflow.context:args:1", "conversation.history", "conversation.history:args:1"), Set.of());
    }
}
