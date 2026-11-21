package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationContracts;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationPolicy;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationPrompt;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Request;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Role;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Status;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations.Tree;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultationPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final UUID plan = UUID.randomUUID(), goal = UUID.randomUUID(), source = UUID.randomUUID();
    private final UUID zone = UUID.randomUUID(), label = UUID.randomUUID(), artifact = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-12T00:00:00Z");
    private final RequirementChanges.Types types = (schema, value) -> validator.validate("collab.VclLiteral", value);
    private final RequirementChanges changes = new RequirementChanges(types);
    private final ConsultationPolicy policy = new ConsultationPolicy(new ConsultationContracts(validator, mapper,
            List.of(role("schema", Set.of("metrics")), role("metrics", Set.of("schema"))),
            Map.of("metrics.read", new ConsultationContracts.LoaderContract("metrics-args-v1", schema())), types), mapper);

    private JsonNode schema() { return mapper.createObjectNode().put("type", "object").put("additionalProperties", false); }
    private Role role(String id, Set<String> delegates) {
        return new Role(id, "1", "result-v1", Consultations.Reason.SCHEMA_REASONING, schema(),
                Set.of("metrics.read"), delegates, Set.of("/zone"), "Use only the supplied evidence.");
    }
    private JsonNode text(String value) { return mapper.createObjectNode().put("type", "TEXT").put("value", value); }
    private Requirements.Snapshot requirements() {
        var slots = List.of(new Requirements.Slot(zone, "/zone", "text-v1", true, Requirements.Status.UNKNOWN, null,
                        Set.of(source), Set.of(), Set.of(), 0, Requirements.Origin.MODEL_PROPOSED),
                new Requirements.Slot(label, "/label", "text-v1", false, Requirements.Status.UNKNOWN, null,
                        Set.of(source), Set.of(), Set.of(), 0, Requirements.Origin.MODEL_PROPOSED));
        return changes.propose(Requirements.Snapshot.empty(plan), 0, UUID.randomUUID(), slots, Set.of(source));
    }
    private Tree tree() { return new Tree(plan, 0, now.plusSeconds(3600), Map.of()); }
    private Request request(UUID parent, String key, String role, long revision) {
        return new Request(parent, key, role, "1", List.of(goal), revision, List.of(artifact), "Assess schema", "result-v1");
    }
    private Tree admit(Tree tree, Request request, Requirements.Snapshot requirements) {
        return policy.request(tree, request, requirements, Set.of(goal), Set.of(artifact), "authority-1");
    }
    private Task byKey(Tree tree, String key) {
        return tree.tasks().values().stream().filter(task -> task.request().requestKey().equals(key)).findFirst().orElseThrow();
    }
    private Tree claim(Tree tree, Task task, Requirements.Snapshot requirements) {
        return policy.claim(tree, task.id(), task.revision(), requirements, UUID.randomUUID(), Map.of(artifact, "v1"),
                "authority-1", now, now.plusSeconds(240), Duration.ofSeconds(60));
    }
    private ObjectNode response(Task task, String outcome) {
        return mapper.createObjectNode().put("taskId", task.id().toString())
                .put("attemptId", task.assignment().attemptId().toString())
                .put("requirementsRevision", task.assignment().requirementsRevision())
                .put("readSetDigest", task.assignment().reads().digest()).put("outcome", outcome);
    }
    private ObjectNode answer(Task task) {
        var result = response(task, "ANSWER");
        result.putObject("payload");
        result.putArray("evidenceRefs").add(artifact.toString());
        result.putArray("unresolvedSlotIds");
        return result;
    }
    private Tree finish(Tree tree, Task task, JsonNode response, Requirements.Snapshot requirements) {
        return policy.complete(tree, task.id(), task.assignment().attemptId(), response, requirements, Map.of(artifact, "v1"),
                "authority-1", Set.of(artifact), now.plusSeconds(1));
    }

    @Test
    void waitingTaskResumesOnlyAfterItsQuestionSlotsAreConfirmed() {
        var requirements = requirements();
        var queued = admit(tree(), request(plan, "one", "schema", requirements.revision()), requirements);
        var running = claim(queued, byKey(queued, "one"), requirements);
        var task = byKey(running, "one");
        var response = response(task, "NEED_USER");
        response.putArray("questions").addObject().putArray("slotIds").add(zone.toString());
        var waitingTask = task.transition(Status.WAITING_USER, task.assignment(), List.of(), response, requirements.revision());
        var waiting = running.replace(waitingTask);
        var unrelated = changes.amend(requirements, requirements.revision(), UUID.randomUUID(), source, Map.of(label, text("label")));
        assertThat(policy.wakeAfterAnswer(waiting, task.id(), waitingTask.revision(), unrelated)).isSameAs(waiting);
        var answered = changes.amend(unrelated, unrelated.revision(), UUID.randomUUID(), source, Map.of(zone, text("UTC")));
        var resumed = policy.wakeAfterAnswer(waiting, task.id(), waitingTask.revision(), answered);
        var resumedTask = resumed.tasks().get(task.id());
        assertThat(resumedTask.status()).isEqualTo(Status.QUEUED);
        assertThat(resumedTask.continuationEpoch()).isEqualTo(1);
        assertThat(resumedTask.exchanges()).isEqualTo(1);
        var next = claim(resumed, resumedTask, answered).tasks().get(task.id());
        assertThat(next.exchanges()).isEqualTo(2);
        assertThat(next.assignment().attemptId()).isNotEqualTo(task.assignment().attemptId());
        assertThat(next.assignment().requirementsRevision()).isEqualTo(answered.revision());
    }

    @Test
    void admissionIsIdempotentAndRejectsConflictingKeysAndForeignScopeBeforeIo() {
        var requirements = requirements();
        var request = request(plan, "one", "schema", requirements.revision());
        var admitted = admit(tree(), request, requirements);
        assertThat(admit(admitted, request, requirements)).isSameAs(admitted);
        var edited = changes.amend(requirements, requirements.revision(), UUID.randomUUID(), source, Map.of(label, text("edited")));
        assertThat(admit(admitted, request, edited)).isSameAs(admitted);
        var changed = new Request(plan, "one", "schema", "1", List.of(goal), requirements.revision(), List.of(artifact), "Other", "result-v1");
        assertThatThrownBy(() -> admit(admitted, changed, requirements)).hasMessage("CONSULTATION_REQUEST_KEY_CONFLICT");
        assertThatThrownBy(() -> policy.request(tree(), request, requirements, Set.of(), Set.of(artifact), "authority-1"))
                .hasMessage("CONSULTATION_SCOPE_CHANGED");
    }

    @Test
    void globalConcurrencyAndFanoutAreBounded() {
        var requirements = requirements();
        var tree = tree();
        for (int i = 0; i < 3; i++) tree = admit(tree, request(plan, "task-" + i, "schema", requirements.revision()), requirements);
        var admitted = tree;
        assertThatThrownBy(() -> admit(admitted, request(plan, "fourth", "schema", requirements.revision()), requirements))
                .hasMessage("CONSULTATION_FANOUT_LIMIT");
        tree = claim(tree, byKey(tree, "task-0"), requirements);
        tree = claim(tree, byKey(tree, "task-1"), requirements);
        var running = tree;
        assertThatThrownBy(() -> claim(running, byKey(running, "task-2"), requirements)).hasMessage("CONSULTATION_CONCURRENCY_LIMIT");
    }

    @Test
    void unrelatedRequirementChangeRebasesWithoutAnotherExchangeButReadDependencyChangeIsStale() {
        var requirements = requirements();
        var queued = admit(tree(), request(plan, "one", "schema", requirements.revision()), requirements);
        var running = claim(queued, byKey(queued, "one"), requirements);
        var task = byKey(running, "one");
        var unrelated = changes.amend(requirements, requirements.revision(), UUID.randomUUID(), source, Map.of(label, text("new label")));
        var accepted = byKey(finish(running, task, answer(task), unrelated), "one");
        assertThat(accepted.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(accepted.acceptedRequirementsRevision()).isEqualTo(unrelated.revision());
        assertThat(accepted.assignment().requirementsRevision()).isEqualTo(requirements.revision());
        assertThat(accepted.exchanges()).isEqualTo(1);
        var changed = changes.amend(requirements, requirements.revision(), UUID.randomUUID(), source, Map.of(zone, text("UTC")));
        assertThat(byKey(finish(running, task, answer(task), changed), "one").status()).isEqualTo(Status.STALE);
    }

    @Test
    void obsoleteAssignmentsRevokedEvidenceAndExpiredLeasesCannotBeAccepted() {
        var requirements = requirements();
        var queued = admit(tree(), request(plan, "one", "schema", requirements.revision()), requirements);
        var running = claim(queued, byKey(queued, "one"), requirements);
        var task = byKey(running, "one");
        var forged = answer(task).put("attemptId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> finish(running, task, forged, requirements)).hasMessage("CONSULTATION_ASSIGNMENT_MISMATCH");
        assertThatThrownBy(() -> policy.complete(running, task.id(), task.assignment().attemptId(), answer(task), requirements,
                Map.of(artifact, "v1"), "authority-1", Set.of(), now.plusSeconds(1))).hasMessage("CONSULTATION_EVIDENCE_UNAVAILABLE");
        var outsideAssignment = UUID.randomUUID();
        var unassignedCitation = answer(task);
        unassignedCitation.putArray("evidenceRefs").add(outsideAssignment.toString());
        var responseSchema = ConsultationPrompt.responseSchema(validator, role("schema", Set.of("metrics")),
                task, requirements, Set.of(artifact, outsideAssignment));
        validator.validateRegistered(responseSchema, answer(task));
        assertThatThrownBy(() -> validator.validateRegistered(responseSchema, unassignedCitation))
                .isInstanceOf(ProtocolValidator.RegisteredViolation.class);
        assertThatThrownBy(() -> policy.complete(running, task.id(), task.assignment().attemptId(), unassignedCitation, requirements,
                Map.of(artifact, "v1"), "authority-1", Set.of(artifact, outsideAssignment), now.plusSeconds(1)))
                .hasMessage("CONSULTATION_EVIDENCE_UNAVAILABLE");
        assertThatThrownBy(() -> policy.complete(running, task.id(), task.assignment().attemptId(), answer(task), requirements,
                Map.of(artifact, "v1"), "authority-1", Set.of(artifact), now.plusSeconds(60))).hasMessage("CONSULTATION_LEASE_OR_DEADLINE_EXPIRED");
        var expired = policy.expireAssignments(running, task.assignment().leaseUntil());
        var expiredTask = expired.tasks().get(task.id());
        assertThat(expiredTask.status()).isEqualTo(Status.EXPIRED);
        assertThat(expiredTask.assignment()).isEqualTo(task.assignment());
        assertThat(expiredTask.exchanges()).isEqualTo(1);
        assertThat(expiredTask.result().path("retryClass").asText()).isEqualTo("UNKNOWN_OUTCOME");
        assertThatThrownBy(() -> claim(expired, expiredTask, requirements)).hasMessage("CONSULTATION_NOT_QUEUED_OR_STALE");
        assertThat(policy.complete(running, task.id(), task.assignment().attemptId(), answer(task), requirements,
                Map.of(), "authority-1", Set.of(), now.plusSeconds(1)).tasks().get(task.id()).status()).isEqualTo(Status.STALE);
        var cancelled = policy.cancel(running, task.id(), task.revision());
        assertThat(cancelled.tasks().get(task.id()).cancellationEpoch()).isEqualTo(1);
        assertThatThrownBy(() -> finish(cancelled, task, answer(task), requirements)).hasMessage("STALE_CONSULTATION_ATTEMPT");
    }

    @Test
    void waitingAndSerializationDoNotResetLifetimeBudgetAndNewKeysCannotEvadeIt() throws Exception {
        var requirements = requirements();
        var tree = admit(tree(), request(plan, "one", "schema", requirements.revision()), requirements);
        for (int i = 0; i < 2; i++) {
            tree = claim(tree, byKey(tree, "one"), requirements);
            var task = byKey(tree, "one");
            var response = response(task, "NEED_DATA");
            response.putArray("requests").addObject().put("loaderId", "metrics.read").put("argumentsSchemaId", "metrics-args-v1").putObject("arguments");
            tree = finish(tree, task, response, requirements);
            assertThat(byKey(tree, "one").status()).isEqualTo(Status.WAITING_DATA);
            tree = mapper.readValue(mapper.writeValueAsBytes(tree), Tree.class);
            tree = policy.wake(tree, task.id(), byKey(tree, "one").revision());
        }
        var exhausted = tree;
        assertThatThrownBy(() -> claim(exhausted, byKey(exhausted, "one"), requirements)).hasMessage("CONSULTATION_LIMIT_REACHED");
        assertThatThrownBy(() -> admit(exhausted, request(plan, "new-key", "schema", requirements.revision()), requirements))
                .hasMessage("CONSULTATION_LIMIT_REACHED");
    }

    @Test
    void declaredDelegationUsesDepthThreeAndCancellationPropagates() throws Exception {
        var requirements = requirements();
        var queued = admit(tree(), request(plan, "parent", "schema", requirements.revision()), requirements);
        var running = claim(queued, byKey(queued, "parent"), requirements);
        var parent = byKey(running, "parent");
        var childRequest = request(parent.id(), "child", "metrics", requirements.revision());
        var response = response(parent, "DELEGATE");
        response.putArray("requests").add(mapper.valueToTree(childRequest));
        var waiting = finish(running, parent, mapper.readTree(response.toString()), requirements);
        var admitted = policy.admitDelegatedChildren(waiting, parent.id(), requirements, Set.of(artifact), "authority-1");
        assertThat(admitted).isEqualTo(admit(waiting, childRequest, requirements));
        assertThat(policy.admitDelegatedChildren(admitted, parent.id(), requirements, Set.of(artifact), "authority-1")).isEqualTo(admitted);
        assertThat(byKey(admitted, "child").depth()).isEqualTo(3);
        assertThatThrownBy(() -> admit(waiting, request(parent.id(), "undeclared", "metrics", requirements.revision()), requirements))
                .hasMessage("UNDECLARED_CHILD_CONSULTATION");
        assertThatThrownBy(() -> policy.wake(admitted, parent.id(), byKey(admitted, "parent").revision()))
                .hasMessage("CONSULTATION_CHILDREN_NOT_COMPLETE");
        var cancelled = policy.cancel(admitted, parent.id(), byKey(admitted, "parent").revision());
        assertThat(cancelled.tasks().values()).allMatch(task -> task.status() == Status.CANCELLED);
        var childRunning = claim(admitted, byKey(admitted, "child"), requirements);
        var child = byKey(childRunning, "child");
        var complete = finish(childRunning, child, answer(child), requirements);
        var waitingParent = byKey(complete, "parent");
        assertThat(byKey(policy.wake(complete, parent.id(), waitingParent.revision()), "parent").status()).isEqualTo(Status.QUEUED);
        assertThat(policy.childResults(complete, waitingParent, requirements, Map.of(artifact, "v1"), "authority-1")
                .getFirst().has("payload")).isTrue();
        var stale = policy.childResults(complete, waitingParent, requirements, Map.of(artifact, "v2"), "authority-1").getFirst();
        assertThat(stale.path("status").asText()).isEqualTo("STALE");
        assertThat(stale.has("payload")).isFalse();
    }

    @Test
    void loaderSchemaAndDelegationParentAreEnforced() {
        var requirements = requirements();
        var queued = admit(tree(), request(plan, "one", "schema", requirements.revision()), requirements);
        var running = claim(queued, byKey(queued, "one"), requirements);
        var task = byKey(running, "one");
        var data = response(task, "NEED_DATA");
        data.putArray("requests").addObject().put("loaderId", "metrics.read").put("argumentsSchemaId", "wrong").putObject("arguments");
        assertThatThrownBy(() -> finish(running, task, data, requirements)).hasMessage("CONSULTATION_LOADER_SCHEMA_CHANGED");
        var delegation = response(task, "DELEGATE");
        delegation.putArray("requests").add(mapper.valueToTree(request(plan, "child", "metrics", requirements.revision())));
        assertThatThrownBy(() -> finish(running, task, delegation, requirements)).hasMessage("CONSULTATION_DELEGATE_PARENT_OR_ROLE_CHANGED");
    }
}
