package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistable task records; no sessions, credentials, clients or transferable grants. */
public final class Consultations {
    private Consultations() { }
    public enum Status { QUEUED, RUNNING, WAITING_DATA, WAITING_CHILD, WAITING_USER, SUCCEEDED, FAILED, CANCELLED, STALE, EXPIRED }
    public enum Reason { SCHEMA_REASONING, POLICY_INTERPRETATION, RULE_COMPOSITION, INDEPENDENT_ASSESSMENT }
    public static final Set<Status> TERMINAL = Set.of(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED, Status.STALE, Status.EXPIRED);

    public record Request(UUID parentTaskId, String requestKey, String skillId, String skillVersion, List<UUID> goalIds,
            long requirementsRevision, List<UUID> inputRefs, String question, String resultSchemaId) {
        public Request {
            goalIds = List.copyOf(goalIds);
            inputRefs = List.copyOf(inputRefs);
        }
    }

    public record Role(String id, String version, String resultSchemaId, Reason reason, JsonNode resultSchema,
            Set<String> loaders, Set<String> delegates, Set<String> requirementPaths, String instructions) {
        public Role {
            resultSchema = resultSchema.deepCopy();
            loaders = Set.copyOf(loaders);
            delegates = Set.copyOf(delegates);
            requirementPaths = Set.copyOf(requirementPaths);
            if (id == null || version == null || resultSchemaId == null || reason == null || instructions == null
                    || instructions.isBlank() || instructions.length() > 8000) throw Requirements.invalid("INVALID_CONSULTATION_ROLE");
        }
        @Override public JsonNode resultSchema() { return resultSchema.deepCopy(); }
    }

    public record ReadSet(Map<UUID, Long> slots, Map<UUID, String> artifacts, String authorityEpoch, String digest) {
        public ReadSet {
            slots = Map.copyOf(slots);
            artifacts = Map.copyOf(artifacts);
        }
    }

    public record Assignment(UUID attemptId, UUID originatingTurnId, long requirementsRevision, long continuationEpoch,
            long cancellationEpoch, ReadSet reads, Instant deadline, Instant leaseUntil) { }

    public record Task(UUID id, Request request, Reason reason, String requestDigest, String obligationDigest, int depth,
            Status status, int exchanges, long revision, long continuationEpoch, long cancellationEpoch,
            Assignment assignment, List<UUID> children, JsonNode result, Long acceptedRequirementsRevision) {
        public Task {
            children = List.copyOf(children);
            result = result == null || result.isNull() ? null : result.deepCopy();
            if (id == null || request == null || reason == null || requestDigest == null || obligationDigest == null
                    || depth < 2 || depth > 3 || status == null || exchanges < 0 || exchanges > 2
                    || revision < 0 || revision > Requirements.MAX_REVISION || continuationEpoch < 0
                    || continuationEpoch > Requirements.MAX_REVISION || cancellationEpoch < 0
                    || cancellationEpoch > Requirements.MAX_REVISION || children.size() > 3
                    || (status == Status.RUNNING && assignment == null)) throw Requirements.invalid("INVALID_CONSULTATION_TASK");
        }
        @Override public JsonNode result() { return result == null ? null : result.deepCopy(); }
        public Task transition(Status next, Assignment current, List<UUID> nextChildren, JsonNode response, Long acceptedRevision) {
            return new Task(id, request, reason, requestDigest, obligationDigest, depth, next, exchanges, revision + 1,
                    continuationEpoch, cancellationEpoch, current, nextChildren, response, acceptedRevision);
        }
    }

    public record Tree(UUID planId, long revision, Instant deadline, Map<UUID, Task> tasks) {
        public Tree {
            tasks = Map.copyOf(tasks);
            if (planId == null || deadline == null || revision < 0 || revision > Requirements.MAX_REVISION || tasks.size() > 12
                    || tasks.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().id()))) {
                throw Requirements.invalid("INVALID_CONSULTATION_TREE");
            }
        }
        public Tree replace(Task task) {
            var next = new java.util.LinkedHashMap<>(tasks);
            next.put(task.id(), task);
            if (revision == Requirements.MAX_REVISION) throw Requirements.invalid("CONSULTATION_REVISION_LIMIT");
            return new Tree(planId, revision + 1, deadline, next);
        }
    }
}
