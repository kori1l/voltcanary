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
            var proposed = mapper.valueToTree(request);
            boolean declared = false;
            for (var child : parent.result().path("requests")) if (child.equals(proposed)) declared = true;
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
        if (old.status() == Status.WAITING_CHILD && (old.children().isEmpty()
                || old.children().stream().map(child -> task(tree, child)).anyMatch(child -> !Consultations.TERMINAL.contains(child.status())))) {
            throw conflict("CONSULTATION_CHILDREN_NOT_COMPLETE");
        }
        return tree.replace(new Task(old.id(), old.request(), old.reason(), old.requestDigest(), old.obligationDigest(), old.depth(), Status.QUEUED,
                old.exchanges(), old.revision() + 1, old.continuationEpoch() + 1, old.cancellationEpoch(), null, old.children(), old.result(), null));
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

    public Tree cancel(Tree tree, UUID id, long expectedRevision) {
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

    private Task task(Tree tree, UUID id) {
        var task = tree.tasks().get(id);
        if (task == null) throw new Requirements.Rejected(404, "CONSULTATION_UNAVAILABLE");
        return task;
    }
    private String digest(Object value) {
        try { return SkillRegistry.digest(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw invalid("CONSULTATION_ENCODING"); }
    }
}
