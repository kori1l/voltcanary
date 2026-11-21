package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
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

    public static JsonNode responseSchema(ProtocolValidator validator, Consultations.Role role,
            Consultations.Task task, Requirements.Snapshot requirements, Set<UUID> authorizedEvidence) {
        var schema = responseSchema(validator, role);
        var evidence = ConsultationContracts.assignedEvidence(task, requirements, authorizedEvidence);
        for (var branch : schema.at("/$defs/collab.WorkerResponse/oneOf")) {
            restrictEvidence(branch.at("/properties/evidenceRefs"), evidence);
        }
        restrictEvidence(schema.at("/$defs/collab.QuestionCandidate/properties/evidenceRefs"), evidence);
        boolean parent = NameConsultation.ROLE.equals(role.id());
        if (!parent && !NameConsultation.CHILD_ROLE.equals(role.id())) return schema;
        boolean unresolved = nameUnresolved(task, requirements);
        String expected = unresolved ? "NEED_USER" : "ANSWER";
        var branches = (com.fasterxml.jackson.databind.node.ArrayNode) schema.at("/$defs/collab.WorkerResponse/oneOf");
        for (int index = branches.size() - 1; index >= 0; index--) {
            String outcome = branches.get(index).at("/properties/outcome/const").asText();
            boolean mayExtend = parent && !unresolved && task.exchanges() == 1 && Set.of("NEED_DATA", "DELEGATE").contains(outcome);
            if (!expected.equals(outcome) && !mayExtend) branches.remove(index);
        }
        if (!unresolved) return schema;
        var properties = (com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/$defs/collab.QuestionCandidate/properties");
        ((com.fasterxml.jackson.databind.node.ObjectNode) properties.path("choices")).put("maxItems", 0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) properties.path("evidenceRefs")).put("maxItems", 0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) properties.path("slotIds")).put("maxItems", 1);
        return schema;
    }

    private static void restrictEvidence(JsonNode schema, Set<UUID> evidence) {
        if (!(schema instanceof com.fasterxml.jackson.databind.node.ObjectNode array)) return;
        if (evidence.isEmpty()) {
            array.put("maxItems", 0);
            return;
        }
        var allowed = array.withObject("/items").putArray("enum");
        evidence.stream().map(UUID::toString).sorted().forEach(allowed::add);
    }

    static boolean nameUnresolved(Consultations.Task task, Requirements.Snapshot requirements) {
        return task.assignment().reads().slots().keySet().stream().map(requirements.slots()::get)
                .anyMatch(slot -> slot == null || slot.status() != Requirements.Status.CONFIRMED);
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
