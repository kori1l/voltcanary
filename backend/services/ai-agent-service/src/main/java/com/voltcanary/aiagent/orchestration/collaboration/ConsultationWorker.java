package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes one durable task inside a native library subgraph, only during an authorized root turn. */
public final class ConsultationWorker {
    public record Evidence(Map<UUID, String> versions, Map<UUID, JsonNode> inputs, Set<UUID> references, String authorityEpoch) {
        public Evidence {
            versions = Map.copyOf(versions);
            inputs = Requirements.copy(inputs);
            references = Set.copyOf(references);
            if (authorityEpoch == null || authorityEpoch.isBlank()) throw Requirements.invalid("CONSULTATION_AUTHORITY_REQUIRED");
        }
        @Override public Map<UUID, JsonNode> inputs() { return Requirements.copy(inputs); }
    }
    @FunctionalInterface
    public interface Authority {
        /** Revalidates the live user/account and every requested artifact; no transferable credentials in the result. */
        Evidence current(IntentPlans.Token turn, Consultations.Task task);
    }
    public record Route(JsonNode policy, RoutedModelCalls.Selection selection, ModelRouting.Availability availability,
            AssistantTraceRecorder.Correlation correlation, RoutedModelCalls.WireAdapter adapter) {
        public Route { policy = policy.deepCopy(); }
        @Override public JsonNode policy() { return policy.deepCopy(); }
    }

    private final CollaborationStore store;
    private final ConsultationPolicy policy;
    private final ConsultationContracts contracts;
    private final RoutedModelCalls calls;
    private final AttemptLedger ledger;
    private final AgentGraphRunner graph;
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;

    public ConsultationWorker(CollaborationStore store, ConsultationPolicy policy, ConsultationContracts contracts,
            RoutedModelCalls calls, AttemptLedger ledger, AgentGraphRunner graph, ObjectMapper mapper, ProtocolValidator validator) {
        this.store = store;
        this.policy = policy;
        this.contracts = contracts;
        this.calls = calls;
        this.ledger = ledger;
        this.graph = graph;
        this.mapper = mapper;
        this.validator = validator;
    }

    public CollaborationStore.State run(IntentPlans.Token root, UUID taskId, long expectedTaskRevision, Authority authority, Route route) {
        if (!route.selection().phase().equals("CONSULT") || !route.correlation().owner().equals(root.owner())
                || !route.correlation().conversationId().equals(root.conversationId())
                || !route.correlation().messageId().equals(root.messageId()) || !route.correlation().turnId().equals(root.requestId())
                || !route.correlation().requestId().equals(root.requestId())) throw new ModelRouting.Stop("CONSULTATION_TRACE_OR_PHASE_MISMATCH");
        var turn = new AttemptLedger.Turn(root.owner(), root.conversationId(), root.planId(), root.requestId());
        var context = new Run(root, taskId, expectedTaskRevision, authority, route, turn);
        var specialist = new AgentGraphRunner.Graph("claim", List.of(
                new AgentGraphRunner.Node("claim", context::claim, Set.of("guard", AgentGraphRunner.END)),
                new AgentGraphRunner.Node("guard", context::guard, Set.of("exchange", AgentGraphRunner.END)),
                new AgentGraphRunner.Node("exchange", context::exchange, Set.of("accept", AgentGraphRunner.END)),
                new AgentGraphRunner.Node("accept", context::accept, Set.of(AgentGraphRunner.END))), List.of());
        graph.runTurn(new AgentGraphRunner.Graph("authorize", List.of(new AgentGraphRunner.Node("authorize", context::authorize,
                Set.of("specialist", AgentGraphRunner.END))), List.of(new AgentGraphRunner.Subgraph("specialist", specialist, AgentGraphRunner.END))),
                root.planId() + ":consult:" + taskId);
        return context.state;
    }

    private final class Run {
        private final IntentPlans.Token root;
        private final UUID id;
        private final long expected;
        private final Authority authority;
        private final Route route;
        private final AttemptLedger.Turn turn;
        private CollaborationStore.State state;
        private Evidence evidence;
        private JsonNode response;
        private String prefix;

        private Run(IntentPlans.Token root, UUID id, long expected, Authority authority, Route route, AttemptLedger.Turn turn) {
            this.root = root;
            this.id = id;
            this.expected = expected;
            this.authority = authority;
            this.route = route;
            this.turn = turn;
        }
        private Consultations.Task task() {
            var task = state.consultations().tasks().get(id);
            if (task == null) throw new Requirements.Rejected(404, "CONSULTATION_UNAVAILABLE");
            return task;
        }
        private String authorize() {
            state = store.read(root.owner(), root.planId());
            evidence = authority.current(root, task());
            if (Consultations.TERMINAL.contains(task().status())) return AgentGraphRunner.END;
            ledger.open(turn, validator.read("routingPolicy", route.policy(), RoutingPolicy.class));
            prefix = "worker:" + root.requestId() + ":" + id + ":" + expected;
            return "specialist";
        }
        private String claim() {
            long remaining = ledger.remainingTimeMillis(turn);
            var deadline = java.time.Instant.now().plusMillis(remaining);
            state = store.update(root, state.revision(), prefix + ":claim", SkillRegistry.digest(prefix + ":claim"), (before, now) ->
                    new CollaborationStore.State(before.revision() + 1, before.requirements(),
                            policy.claim(before.consultations(), id, expected, before.requirements(), root.requestId(), evidence.versions(),
                                    evidence.authorityEpoch(), now, deadline, Duration.ofSeconds(120))));
            return task().status() == Consultations.Status.RUNNING ? "guard" : AgentGraphRunner.END;
        }
        private String guard() {
            evidence = authority.current(root, task());
            var attempt = task().assignment().attemptId();
            state = store.update(root, state.revision(), prefix + ":guard:" + state.revision(),
                    SkillRegistry.digest(attempt + ":guard:" + evidence.authorityEpoch() + ":" + new java.util.TreeMap<>(evidence.versions())), (before, now) ->
                    new CollaborationStore.State(before.revision() + 1, before.requirements(),
                            policy.checkCurrent(before.consultations(), id, attempt, before.requirements(), evidence.versions(), evidence.authorityEpoch(), now)));
            return task().status() == Consultations.Status.RUNNING ? "exchange" : AgentGraphRunner.END;
        }
        private String exchange() {
            try {
                response = calls.consult(turn, route.policy(), route.selection(), contracts, task(), state.requirements(), evidence.inputs(),
                        evidence.references(), route.availability(), route.correlation(), route.adapter());
            } catch (ModelRouting.Stop stopped) {
                // A concurrent duplicate has no authority to finish somebody else's in-flight reservation.
                if (stopped.getMessage().startsWith("CALL_ALREADY_RESERVED:")) return AgentGraphRunner.END;
                if (Set.of("CALL_ID_CONFLICT", "POLICY_CONFLICT").contains(stopped.getMessage())) throw stopped;
                response = failure(stopped.getMessage(), stopped.getMessage().equals("PROVIDER_UNCERTAIN") ? "UNKNOWN_OUTCOME" : "NEVER");
            } catch (IllegalArgumentException rejected) {
                response = failure("CONSULTATION_PROPOSAL_INVALID", "NEVER");
            }
            return "accept";
        }
        private String accept() {
            evidence = authority.current(root, task());
            var attempt = task().assignment().attemptId();
            // Re-read under the root fence, allowing unrelated requirements/task changes to rebase.
            state = store.read(root.owner(), root.planId());
            state = store.update(root, state.revision(), prefix + ":accept", SkillRegistry.digest(response.toString()), (before, now) ->
                    new CollaborationStore.State(before.revision() + 1, before.requirements(),
                            policy.complete(before.consultations(), id, attempt, response, before.requirements(), evidence.versions(),
                                    evidence.authorityEpoch(), evidence.references(), now)));
            return AgentGraphRunner.END;
        }
        private JsonNode failure(String code, String retryClass) {
            var assignment = task().assignment();
            return mapper.createObjectNode().put("taskId", id.toString()).put("attemptId", assignment.attemptId().toString())
                    .put("requirementsRevision", assignment.requirementsRevision()).put("readSetDigest", assignment.reads().digest())
                    .put("outcome", "FAILURE").put("code", code).put("retryClass", retryClass);
        }
    }
}
