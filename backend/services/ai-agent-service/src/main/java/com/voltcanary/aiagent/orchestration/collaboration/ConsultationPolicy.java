package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Assignment;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.ReadSet;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Request;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Status;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Tree;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.conflict;
import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.invalid;

/** Pure admission/result policy; the store supplies current authority, DB time, leases and durable CAS. */
public final class ConsultationPolicy {
    private final ConsultationContracts contracts;
    private final ObjectMapper mapper;

    public ConsultationPolicy(ConsultationContracts contracts, ObjectMapper mapper) {
        this.contracts = contracts;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Tree request(Tree tree, Request request, Requirements.Snapshot requirements, Set<UUID> allowedGoals,
            Set<UUID> authorizedInputs, String authorityEpoch) {
        var role = contracts.role(request);
        if (!tree.planId().equals(requirements.planId()) || !allowedGoals.containsAll(request.goalIds())
                || !authorizedInputs.containsAll(request.inputRefs())) throw invalid("CONSULTATION_SCOPE_CHANGED");
        String digest = digest(request);
        for (var existing : tree.tasks().values()) {
            if (existing.request().parentTaskId().equals(request.parentTaskId())
                    && existing.request().requestKey().equals(request.requestKey())) {
                if (!existing.requestDigest().equals(digest)) throw conflict("CONSULTATION_REQUEST_KEY_CONFLICT");
                return tree;
            }
        }
        Task parent = null;
        int depth = 2;
        if (!request.parentTaskId().equals(tree.planId())) {
            parent = task(tree, request.parentTaskId());
            if (parent.status() != Status.WAITING_CHILD || parent.result() == null
                    || !parent.result().path("outcome").asText().equals("DELEGATE")
                    || !parent.request().goalIds().containsAll(request.goalIds())
                    || !contracts.role(parent.request()).delegates().contains(request.skillId())) {
                throw invalid("CONSULTATION_PARENT_NOT_DELEGATING");
            }
            boolean declared = false;
            for (var child : parent.result().path("requests")) {
                if (request.equals(decodeChild(child))) declared = true;
            }
            if (!declared) throw invalid("UNDECLARED_CHILD_CONSULTATION");
            depth = parent.depth() + 1;
        }
        if (depth > 3) throw invalid("DELEGATION_DEPTH_EXCEEDED");
        if (request.requirementsRevision() != requirements.revision()
                && (parent == null || parent.acceptedRequirementsRevision() == null
                || parent.acceptedRequirementsRevision() != requirements.revision()
                || request.requirementsRevision() != parent.assignment().requirementsRevision())) {
            throw conflict("REQUIREMENTS_REVISION_CHANGED");
        }
        var reads = reads(requirements, role.requirementPaths(), request.inputRefs(), Map.of(), authorityEpoch, true);
        String obligation = digest(List.of(role.id(), role.version(), role.reason(),
                request.goalIds().stream().sorted().toList(), request.inputRefs().stream().sorted().toList(), reads.slots()));
        for (Task ancestor = parent; ancestor != null; ancestor = ancestor.request().parentTaskId().equals(tree.planId())
                ? null : task(tree, ancestor.request().parentTaskId())) {
            if (ancestor.obligationDigest().equals(obligation)) throw invalid("DELEGATION_CYCLE");
        }
        if (tree.tasks().values().stream().filter(task -> task.obligationDigest().equals(obligation)).mapToInt(Task::exchanges).sum() >= 2) {
            throw invalid("CONSULTATION_LIMIT_REACHED");
        }
        if (tree.tasks().size() >= 12) throw invalid("CONSULTATION_NODE_LIMIT");
        if (tree.tasks().values().stream().filter(task -> task.request().parentTaskId().equals(request.parentTaskId())).count() >= 3) {
            throw invalid("CONSULTATION_FANOUT_LIMIT");
        }
        var id = UUID.nameUUIDFromBytes((tree.planId() + ":" + request.parentTaskId() + ":" + request.requestKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var admitted = new Task(id, request, role.reason(), digest, obligation, depth, Status.QUEUED, 0, 0, 0, 0, null, List.of(), null, null);
        var next = tree.replace(admitted);
        if (parent != null) {
            var children = new ArrayList<>(parent.children());
            children.add(id);
            next = next.replace(parent.transition(Status.WAITING_CHILD, parent.assignment(), children, parent.result(), parent.acceptedRequirementsRevision()));
        }
        return next;
    }

    public Tree claim(Tree tree, UUID taskId, long expectedRevision, Requirements.Snapshot requirements, UUID turnId,
            Map<UUID, String> currentArtifacts, String authorityEpoch, Instant now, Instant turnDeadline, Duration lease) {
        tree = expireAssignments(tree, now);
        var old = task(tree, taskId);
        if (!tree.planId().equals(requirements.planId())) throw invalid("CONSULTATION_SCOPE_CHANGED");
        if (old.revision() != expectedRevision || old.status() != Status.QUEUED) throw conflict("CONSULTATION_NOT_QUEUED_OR_STALE");
        if (lease.compareTo(Duration.ofSeconds(10)) < 0 || lease.compareTo(Duration.ofSeconds(120)) > 0) throw invalid("INVALID_CONSULTATION_LEASE");
        Instant deadline = tree.deadline().isBefore(turnDeadline) ? tree.deadline() : turnDeadline;
        if (!deadline.isAfter(now)) throw new Requirements.Rejected(410, "CONSULTATION_EXPIRED");
        if (tree.tasks().values().stream().filter(task -> task.status() == Status.RUNNING).count() >= 2) throw conflict("CONSULTATION_CONCURRENCY_LIMIT");
        int lineageExchanges = tree.tasks().values().stream().filter(task -> task.obligationDigest().equals(old.obligationDigest())).mapToInt(Task::exchanges).sum();
        if (old.exchanges() >= 2 || lineageExchanges >= 2) throw invalid("CONSULTATION_LIMIT_REACHED");
        var role = contracts.role(old.request());
        var manifest = reads(requirements, role.requirementPaths(), old.request().inputRefs(), currentArtifacts, authorityEpoch, false);
        Instant until = now.plus(lease).isBefore(deadline) ? now.plus(lease) : deadline;
        var assignment = new Assignment(UUID.randomUUID(), turnId, requirements.revision(), old.continuationEpoch(), old.cancellationEpoch(), manifest, deadline, until);
        return tree.replace(new Task(old.id(), old.request(), old.reason(), old.requestDigest(), old.obligationDigest(), old.depth(), Status.RUNNING,
                old.exchanges() + 1, old.revision() + 1, old.continuationEpoch(), old.cancellationEpoch(), assignment, old.children(), old.result(), null));
    }

    /** An expired assignment may have sent its reserved exchange. Preserve its identity and never requeue it. */
    public Tree expireAssignments(Tree tree, Instant now) {
        var next = tree;
        for (var task : tree.tasks().values()) {
            var assignment = task.assignment();
            if (task.status() != Status.RUNNING || assignment.leaseUntil().isAfter(now)) continue;
            var diagnostic = mapper.createObjectNode().put("taskId", task.id().toString())
                    .put("attemptId", assignment.attemptId().toString())
                    .put("requirementsRevision", assignment.requirementsRevision()).put("readSetDigest", assignment.reads().digest())
                    .put("outcome", "FAILURE").put("code", "CONSULTATION_ASSIGNMENT_EXPIRED")
                    .put("retryClass", "UNKNOWN_OUTCOME");
            next = next.replace(task.transition(Status.EXPIRED, assignment, task.children(), diagnostic, null));
        }
        return next;
    }

    public Tree complete(Tree tree, UUID id, UUID attemptId, JsonNode response, Requirements.Snapshot requirements,
            Map<UUID, String> currentArtifacts, String authorityEpoch, Set<UUID> authorizedEvidence, Instant now) {
        var old = task(tree, id);
        if (!tree.planId().equals(requirements.planId())) throw invalid("CONSULTATION_SCOPE_CHANGED");
        var assignment = old.assignment();
        if (old.status() != Status.RUNNING || assignment == null || !assignment.attemptId().equals(attemptId)
                || assignment.continuationEpoch() != old.continuationEpoch() || assignment.cancellationEpoch() != old.cancellationEpoch()) {
            throw conflict("STALE_CONSULTATION_ATTEMPT");
        }
        if (!assignment.deadline().isAfter(now) || !assignment.leaseUntil().isAfter(now)) throw conflict("CONSULTATION_LEASE_OR_DEADLINE_EXPIRED");
        contracts.assignment(old, response);
        ReadSet current;
        try {
            current = reads(requirements, contracts.role(old.request()).requirementPaths(), old.request().inputRefs(), currentArtifacts, authorityEpoch, false);
        } catch (Requirements.Rejected revoked) {
            if (!Set.of("CONSULTATION_INPUT_REVOKED", "CONSULTATION_REQUIREMENT_PATH_UNAVAILABLE").contains(revoked.getMessage())) throw revoked;
            return tree.replace(old.transition(Status.STALE, assignment, old.children(), null, null));
        }
        if (!current.slots().equals(assignment.reads().slots()) || !current.artifacts().equals(assignment.reads().artifacts())
                || !authorityEpoch.equals(assignment.reads().authorityEpoch())) {
            return tree.replace(old.transition(Status.STALE, assignment, old.children(), null, null));
        }
        contracts.response(old, response, requirements, authorizedEvidence);
        Status next = switch (response.path("outcome").asText()) {
            case "ANSWER" -> Status.SUCCEEDED;
            case "NEED_DATA" -> Status.WAITING_DATA;
            case "DELEGATE" -> Status.WAITING_CHILD;
            case "NEED_USER" -> Status.WAITING_USER;
            case "UNSUPPORTED", "FAILURE" -> Status.FAILED;
            default -> throw invalid("UNKNOWN_CONSULTATION_OUTCOME");
        };
        return tree.replace(old.transition(next, assignment, old.children(), response, requirements.revision()));
    }

    public Tree wake(Tree tree, UUID id, long expectedRevision) {
        var old = task(tree, id);
        if (old.revision() != expectedRevision || !Set.of(Status.WAITING_CHILD, Status.WAITING_DATA, Status.WAITING_USER).contains(old.status())) {
            throw conflict("CONSULTATION_NOT_WAITING_OR_STALE");
        }
        if (old.status() == Status.WAITING_CHILD && !childrenComplete(tree, old)) {
            throw conflict("CONSULTATION_CHILDREN_NOT_COMPLETE");
        }
        return tree.replace(new Task(old.id(), old.request(), old.reason(), old.requestDigest(), old.obligationDigest(), old.depth(), Status.QUEUED,
                old.exchanges(), old.revision() + 1, old.continuationEpoch() + 1, old.cancellationEpoch(), null, old.children(), old.result(), null));
    }

    /** Admit the entire validated delegation in the same snapshot as its parent result. */
    public Tree admitDelegatedChildren(Tree tree, UUID parentId, Requirements.Snapshot requirements,
            Set<UUID> authorizedInputs, String authorityEpoch) {
        var parent = task(tree, parentId);
        if (parent.status() != Status.WAITING_CHILD) return tree;
        var next = tree;
        var keys = new HashSet<String>();
        for (var declaration : parent.result().path("requests")) {
            var child = decodeChild(declaration);
            if (!keys.add(child.requestKey())) throw invalid("CONSULTATION_DUPLICATE_CHILD_KEY");
            next = request(next, child, requirements, Set.copyOf(parent.request().goalIds()), authorizedInputs, authorityEpoch);
        }
        return next;
    }

    private Request decodeChild(JsonNode declaration) {
        try {
            return mapper.treeToValue(declaration, Request.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid("CONSULTATION_CHILD_REQUEST_INVALID");
        }
    }

    static boolean childrenComplete(Tree tree, Task parent) {
        if (parent.status() != Status.WAITING_CHILD || parent.result() == null
                || !"DELEGATE".equals(parent.result().path("outcome").asText())) return false;
        var requests = parent.result().path("requests");
        if (requests.isEmpty() || parent.children().size() != requests.size()) return false;
        var completed = new HashSet<String>();
        for (UUID id : parent.children()) {
            var child = tree.tasks().get(id);
            if (child == null || !parent.id().equals(child.request().parentTaskId())
                    || !Consultations.TERMINAL.contains(child.status())) return false;
            completed.add(child.request().requestKey());
        }
        return completed.size() == requests.size()
                && java.util.stream.StreamSupport.stream(requests.spliterator(), false)
                        .allMatch(request -> completed.contains(request.path("requestKey").asText()));
    }

    public List<JsonNode> childResults(Tree tree, Task parent, Requirements.Snapshot requirements,
            Map<UUID, String> artifacts, String authorityEpoch) {
        return parent.children().stream().sorted().map(id -> {
            var child = task(tree, id);
            if (!parent.id().equals(child.request().parentTaskId()) || !Consultations.TERMINAL.contains(child.status())) {
                throw conflict("CONSULTATION_CHILDREN_NOT_COMPLETE");
            }
            var result = mapper.createObjectNode().put("taskId", id.toString()).put("status", child.status().name());
            var assignment = child.assignment();
            if (child.status() == Status.SUCCEEDED && assignment != null && child.result() != null) {
                var reads = assignment.reads();
                boolean fresh = authorityEpoch.equals(reads.authorityEpoch())
                        && reads.slots().entrySet().stream().allMatch(entry -> {
                            var slot = requirements.slots().get(entry.getKey());
                            return slot != null && slot.revision() == entry.getValue();
                        })
                        && reads.artifacts().entrySet().stream().allMatch(entry -> entry.getValue().equals(artifacts.get(entry.getKey())));
                if (fresh) result.set("payload", child.result().path("payload"));
                else result.put("status", Status.STALE.name());
            }
            return (JsonNode) result;
        }).toList();
    }

    public Tree wakeAfterAnswer(Tree tree, UUID id, long expectedRevision, Requirements.Snapshot requirements) {
        var task = task(tree, id);
        if (task.revision() != expectedRevision || task.status() != Status.WAITING_USER) {
            throw conflict("CONSULTATION_NOT_WAITING_OR_STALE");
        }
        if (!tree.planId().equals(requirements.planId()) || task.assignment() == null || task.result() == null) {
            throw invalid("CONSULTATION_SCOPE_CHANGED");
        }
        var requested = new HashSet<UUID>();
        task.result().path("questions").forEach(question ->
                question.path("slotIds").forEach(slot -> requested.add(UUID.fromString(slot.asText()))));
        if (requested.isEmpty() || !requested.stream().allMatch(slotId -> {
            var slot = requirements.slots().get(slotId);
            var prior = task.assignment().reads().slots().get(slotId);
            return slot != null && prior != null && slot.revision() > prior && slot.status() == Requirements.Status.CONFIRMED;
        })) return tree;
        return wake(tree, id, expectedRevision);
    }

    /** Freshness guard before provider I/O; loss of a dependency invalidates the task without spending another call. */
    public Tree checkCurrent(Tree tree, UUID id, UUID attemptId, Requirements.Snapshot requirements,
            Map<UUID, String> currentArtifacts, String authorityEpoch, Instant now) {
        var old = task(tree, id);
        var assignment = old.assignment();
        if (!tree.planId().equals(requirements.planId()) || old.status() != Status.RUNNING || assignment == null
                || !assignment.attemptId().equals(attemptId) || assignment.continuationEpoch() != old.continuationEpoch()
                || assignment.cancellationEpoch() != old.cancellationEpoch()) throw conflict("STALE_CONSULTATION_ATTEMPT");
        if (!assignment.leaseUntil().isAfter(now) || !assignment.deadline().isAfter(now)) throw conflict("CONSULTATION_LEASE_OR_DEADLINE_EXPIRED");
        ReadSet current;
        try {
            current = reads(requirements, contracts.role(old.request()).requirementPaths(), old.request().inputRefs(), currentArtifacts, authorityEpoch, false);
        } catch (Requirements.Rejected revoked) {
            if (!Set.of("CONSULTATION_INPUT_REVOKED", "CONSULTATION_REQUIREMENT_PATH_UNAVAILABLE").contains(revoked.getMessage())) throw revoked;
            return tree.replace(old.transition(Status.STALE, assignment, old.children(), null, null));
        }
        if (!current.equals(assignment.reads())) return tree.replace(old.transition(Status.STALE, assignment, old.children(), null, null));
        return tree;
    }

    public static Tree cancel(Tree tree, UUID id, long expectedRevision) {
        var target = task(tree, id);
        if (target.revision() != expectedRevision) throw conflict("CONSULTATION_REVISION_CHANGED");
        var cancelled = new HashSet<UUID>();
        cancelled.add(id);
        boolean expanded;
        do {
            expanded = false;
            for (var candidate : tree.tasks().values()) {
                if (cancelled.contains(candidate.request().parentTaskId()) && cancelled.add(candidate.id())) expanded = true;
            }
        } while (expanded);
        var next = tree;
        for (var task : tree.tasks().values()) if (cancelled.contains(task.id()) && !Consultations.TERMINAL.contains(task.status())) {
            next = next.replace(new Task(task.id(), task.request(), task.reason(), task.requestDigest(), task.obligationDigest(), task.depth(), Status.CANCELLED,
                    task.exchanges(), task.revision() + 1, task.continuationEpoch(), task.cancellationEpoch() + 1,
                    null, task.children(), null, null));
        }
        return next;
    }

    private ReadSet reads(Requirements.Snapshot requirements, Set<String> paths, List<UUID> references,
            Map<UUID, String> artifacts, String authorityEpoch, boolean admissionOnly) {
        var slots = new LinkedHashMap<UUID, Long>();
        requirements.slots().values().stream().filter(slot -> paths.contains(slot.path())).forEach(slot -> slots.put(slot.id(), slot.revision()));
        if (slots.size() != paths.size()) throw invalid("CONSULTATION_REQUIREMENT_PATH_UNAVAILABLE");
        var selected = new LinkedHashMap<UUID, String>();
        for (UUID id : references) {
            if (!admissionOnly && (!artifacts.containsKey(id) || artifacts.get(id).isBlank())) throw invalid("CONSULTATION_INPUT_REVOKED");
            selected.put(id, artifacts.getOrDefault(id, "ADMISSION_ONLY"));
        }
        return new ReadSet(slots, selected, authorityEpoch, digest(List.of(slots, selected, authorityEpoch)));
    }

    private static Task task(Tree tree, UUID id) {
        var task = tree.tasks().get(id);
        if (task == null) throw new Requirements.Rejected(404, "CONSULTATION_UNAVAILABLE");
        return task;
    }
    private String digest(Object value) {
        try { return SkillRegistry.digest(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw invalid("CONSULTATION_ENCODING"); }
    }
}
