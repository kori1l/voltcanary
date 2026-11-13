package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Exact declared read set only. The caller resolves artifact values under the current user session before this pure projection. */
public final class ConsultationPrompt {
    private ConsultationPrompt() { }

    public static JsonNode request(ObjectMapper mapper, Consultations.Task task, Consultations.Role role,
            Requirements.Snapshot requirements, Map<UUID, JsonNode> inputs) {
        var assignment = task.assignment();
        if (task.status() != Consultations.Status.RUNNING || assignment == null
                || !inputs.keySet().containsAll(assignment.reads().artifacts().keySet())) throw Requirements.invalid("CONSULTATION_PROMPT_SCOPE_CHANGED");
        var request = mapper.createObjectNode().put("version", Requirements.VERSION).put("kind", "consultation.assignment")
                .put("planId", requirements.planId().toString()).put("taskId", task.id().toString())
                .put("parentTaskId", task.request().parentTaskId().toString()).put("skillId", role.id()).put("skillVersion", role.version())
                .put("question", task.request().question()).put("instructions", role.instructions())
                .put("attemptId", assignment.attemptId().toString()).put("requirementsRevision", assignment.requirementsRevision())
                .put("readSetDigest", assignment.reads().digest());
        request.set("goalIds", mapper.valueToTree(task.request().goalIds()));
        var slots = request.putArray("slots");
        assignment.reads().slots().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var slot = requirements.slots().get(entry.getKey());
            if (slot == null || slot.revision() != entry.getValue()) throw Requirements.invalid("CONSULTATION_PROMPT_SCOPE_CHANGED");
            var projected = mapper.valueToTree(slot);
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) projected;
            for (String field : java.util.List.of("sourceMessages", "evidence", "dependsOn")) {
                var ordered = new java.util.ArrayList<String>();
                object.path(field).forEach(id -> ordered.add(id.asText()));
                object.putArray(field).addAll(ordered.stream().sorted().map(com.fasterxml.jackson.databind.node.TextNode::valueOf).toList());
            }
            slots.add(projected);
        });
        var artifacts = request.putObject("inputs");
        assignment.reads().artifacts().keySet().stream().sorted().forEach(id -> artifacts.set(id.toString(), inputs.get(id).deepCopy()));
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > ProtocolValidator.MAX_BYTES) {
            throw Requirements.invalid("CONSULTATION_PROMPT_SIZE_LIMIT");
        }
        return request;
    }

    public static JsonNode responseSchema(ProtocolValidator validator, Consultations.Role role) {
        var schema = validator.projection("collab.WorkerResponse");
        var result = role.resultSchema();
        namespaceResult(result);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("$defs")).set("collab.roleResult", result);
        for (var branch : schema.at("/$defs/collab.WorkerResponse/oneOf")) {
            if (branch.at("/properties/outcome/const").asText().equals("ANSWER")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) branch.path("properties")).putObject("payload").put("$ref", "#/$defs/collab.roleResult");
            }
        }
        return schema;
    }

    private static void namespaceResult(JsonNode value) {
        if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            object.remove("$id");
            if (object.has("$ref")) {
                String reference = object.path("$ref").asText();
                if (!reference.equals("#") && !reference.startsWith("#/")) throw Requirements.invalid("EXTERNAL_CONSULTATION_SCHEMA_REFERENCE");
                object.put("$ref", "#/$defs/collab.roleResult" + reference.substring(1));
            }
        }
        if (value.isContainerNode()) value.forEach(ConsultationPrompt::namespaceResult);
    }
}
