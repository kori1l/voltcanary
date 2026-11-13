package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Assignment;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Request;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Role;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.invalid;

/** Canonical envelopes plus registry-owned semantic contracts and server-issued assignment echoes. */
public final class ConsultationContracts {
    public record LoaderContract(String schemaId, JsonNode schema) {
        public LoaderContract { schema = schema.deepCopy(); }
        @Override public JsonNode schema() { return schema.deepCopy(); }
    }
    private final ProtocolValidator validator;
    private final ObjectMapper mapper;
    private final Map<String, Role> roles;
    private final Map<String, LoaderContract> loaderSchemas;
    private final RequirementChanges.Types types;

    public ConsultationContracts(ProtocolValidator validator, ObjectMapper mapper, List<Role> registered,
            Map<String, LoaderContract> loaderSchemas, RequirementChanges.Types types) {
        this.validator = validator;
        this.mapper = mapper;
        this.types = types;
        var byId = new LinkedHashMap<String, Role>();
        for (var role : registered) {
            if (byId.putIfAbsent(role.id(), role) != null) throw invalid("DUPLICATE_CONSULTATION_ROLE");
        }
        this.roles = Map.copyOf(byId);
        this.loaderSchemas = Map.copyOf(loaderSchemas);
        for (var role : roles.values()) {
            if (!roles.keySet().containsAll(role.delegates()) || !loaderSchemas.keySet().containsAll(role.loaders())) {
                throw invalid("UNREGISTERED_CONSULTATION_CAPABILITY");
            }
        }
    }

    public Role role(Request request) {
        validator.validate("collab.ConsultationRequest", mapper.valueToTree(request));
        var role = roles.get(request.skillId());
        if (role == null || !role.version().equals(request.skillVersion()) || !role.resultSchemaId().equals(request.resultSchemaId())) {
            throw invalid("CONSULTATION_ROLE_VERSION_UNAVAILABLE");
        }
        return role;
    }

    /** Public metadata for capabilities already allowed by this registered role. Never a model-writable registry. */
    public JsonNode capabilities(Role role) {
        var result = mapper.createObjectNode();
        var loaders = result.putArray("loaders");
        role.loaders().stream().sorted().forEach(id -> {
            var contract = loaderSchemas.get(id);
            loaders.addObject().put("loaderId", id).put("argumentsSchemaId", contract.schemaId()).set("argumentsSchema", contract.schema());
        });
        var delegates = result.putArray("delegates");
        role.delegates().stream().sorted().forEach(id -> {
            var delegate = roles.get(id);
            delegates.addObject().put("skillId", id).put("skillVersion", delegate.version()).put("resultSchemaId", delegate.resultSchemaId())
                    .put("reason", delegate.reason().name());
        });
        return result;
    }

    public void response(Task task, JsonNode response, Requirements.Snapshot requirements, Set<UUID> authorizedEvidence) {
        assignment(task, response);
        var assignment = task.assignment();
        var role = role(task.request());
        switch (response.path("outcome").asText()) {
            case "ANSWER" -> {
                validator.validateRegistered(role.resultSchema(), response.path("payload"));
                evidence(response.path("evidenceRefs"), authorizedEvidence);
                for (var id : response.path("unresolvedSlotIds")) {
                    UUID slot = UUID.fromString(id.asText());
                    requireSlot(assignment, slot);
                    requireUnresolved(requirements, slot);
                }
            }
            case "NEED_DATA" -> {
                for (var request : response.path("requests")) {
                    String loader = request.path("loaderId").asText();
                    if (!role.loaders().contains(loader)) throw invalid("CONSULTATION_LOADER_UNAVAILABLE");
                    var registered = loaderSchemas.get(loader);
                    if (!registered.schemaId().equals(request.path("argumentsSchemaId").asText())) throw invalid("CONSULTATION_LOADER_SCHEMA_CHANGED");
                    validator.validateRegistered(registered.schema(), request.path("arguments"));
                }
            }
            case "DELEGATE" -> {
                for (var request : response.path("requests")) {
                    var child = validator.read("collab.ConsultationRequest", request, Request.class);
                    if (!child.parentTaskId().equals(task.id()) || !role.delegates().contains(child.skillId())
                            || !task.request().goalIds().containsAll(child.goalIds())
                            || !assignment.reads().artifacts().keySet().containsAll(child.inputRefs())) {
                        throw invalid("CONSULTATION_DELEGATE_PARENT_OR_ROLE_CHANGED");
                    }
                    role(child);
                }
            }
            case "NEED_USER" -> validateQuestions(response, assignment, requirements, authorizedEvidence);
            case "UNSUPPORTED", "FAILURE" -> { }
            default -> throw invalid("UNKNOWN_CONSULTATION_OUTCOME");
        }
    }

    public void assignment(Task task, JsonNode response) {
        validator.validate("collab.WorkerResponse", response);
        var assignment = task.assignment();
        if (assignment == null || !task.id().toString().equals(response.path("taskId").asText())
                || !assignment.attemptId().toString().equals(response.path("attemptId").asText())
                || assignment.requirementsRevision() != response.path("requirementsRevision").asLong()
                || !assignment.reads().digest().equals(response.path("readSetDigest").asText())) {
            throw invalid("CONSULTATION_ASSIGNMENT_MISMATCH");
        }
    }

    private void validateQuestions(JsonNode response, Assignment assignment, Requirements.Snapshot requirements, Set<UUID> authorizedEvidence) {
        for (var question : response.path("questions")) {
            evidence(question.path("evidenceRefs"), authorizedEvidence);
            var allowed = new java.util.HashSet<UUID>();
            for (var id : question.path("slotIds")) {
                UUID slot = UUID.fromString(id.asText());
                requireSlot(assignment, slot);
                requireUnresolved(requirements, slot);
                allowed.add(slot);
            }
            for (var choice : question.path("choices")) choice.path("bindings").fields().forEachRemaining(entry -> {
                UUID slot = UUID.fromString(entry.getKey());
                if (!allowed.contains(slot)) throw invalid("CONSULTATION_QUESTION_SLOT_CHANGED");
                var declaration = requirements.slots().get(slot);
                if (declaration == null) throw invalid("UNKNOWN_REQUIREMENT_SLOT");
                types.validate(declaration.schemaId(), entry.getValue());
            });
        }
    }

    private void evidence(JsonNode references, Set<UUID> authorized) {
        for (var reference : references) if (!authorized.contains(UUID.fromString(reference.asText()))) {
            throw invalid("CONSULTATION_EVIDENCE_UNAVAILABLE");
        }
    }
    private void requireSlot(Assignment assignment, UUID id) {
        if (!assignment.reads().slots().containsKey(id)) throw invalid("CONSULTATION_UNDECLARED_SLOT");
    }
    private void requireUnresolved(Requirements.Snapshot requirements, UUID id) {
        var slot = requirements.slots().get(id);
        if (slot == null) throw invalid("UNKNOWN_REQUIREMENT_SLOT");
        if (Set.of(Requirements.Status.CONFIRMED, Requirements.Status.DERIVED).contains(slot.status())) {
            throw invalid("CONSULTATION_KNOWN_SLOT_NOT_UNRESOLVED");
        }
    }
}
