package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlannerResponse;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponse;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.util.Set;
import java.util.UUID;

/** All A3 model phases enter A2's same durable turn + parent-plan ledger. No adapter retry loop. */
public final class RoutedIntentModels implements OrchestrationPorts.Models {
    /** A4 owns bounded request assembly. A3 supplies actual persisted input and exact pending question. */
    public interface Requests {
        JsonNode planner(IntentPlans.ModelInput input, OrchestrationPorts.Session session);
        JsonNode step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId);
        default void verifyForDispatch(IntentPlans.ModelInput input, OrchestrationPorts.Session session, JsonNode request) { }
        default JsonNode repair(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId,
                com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic diagnostic,
                com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Command rejected) { throw IntentPlans.reject("REPAIR_UNAVAILABLE"); }
        default int maxContextContinuations() { return 0; }
        default JsonNode continueStep(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId, JsonNode previous,
                java.util.List<com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.LoaderRequest> requests) {
            throw IntentPlans.reject("CONTEXT_CONTINUATION_UNAVAILABLE");
        }
    }
    public record Route(JsonNode policy, RoutedModelCalls.Selection selection, Set<String> authorized, Set<String> capabilities,
            ModelRouting.Availability availability, RoutedModelCalls.WireAdapter adapter, java.util.function.Consumer<JsonNode> proposalCheck) {
        public Route(JsonNode policy, RoutedModelCalls.Selection selection, Set<String> authorized, Set<String> capabilities,
                ModelRouting.Availability availability, RoutedModelCalls.WireAdapter adapter) {
            this(policy, selection, authorized, capabilities, availability, adapter, proposal -> { });
        }
    }
    @FunctionalInterface
    public interface Policies {
        Route route(IntentPlans.ModelInput input, OrchestrationPorts.Session session, String phase);
    }
    private final RoutedModelCalls calls;
    private final Requests requests;
    private final Policies policies;
    private final IntentPlanStore store;
    private final ProtocolValidator validator;
    private final boolean automaticCorrections;
    private final AgentGraphRunner graphRunner;

    public RoutedIntentModels(RoutedModelCalls calls, Requests requests, Policies policies, IntentPlanStore store,
            ProtocolValidator validator, AgentGraphRunner graphRunner) {
        this(calls, requests, policies, store, validator, true, graphRunner);
    }
    public RoutedIntentModels(RoutedModelCalls calls, Requests requests, Policies policies, IntentPlanStore store,
            ProtocolValidator validator, boolean automaticCorrections, AgentGraphRunner graphRunner) {
        this.calls = calls;
        this.requests = requests;
        this.policies = policies;
        this.store = store;
        this.validator = validator;
        this.automaticCorrections = automaticCorrections;
        this.graphRunner = graphRunner;
    }
    @Override public boolean automaticCorrections() { return automaticCorrections; }

    @Override
    public PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
        var request = requests.planner(input, session);
        return validator.read("plannerResponse", invoke(input, session, callId, "PLANNER", request), PlannerResponse.class);
    }

    @Override
    public StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
        var request = requests.step(input, session, callId);
        return stepCall(input, session, callId, request, "STEP");
    }
    @Override
    public StepResponse repair(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId,
            com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic diagnostic,
            com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Command rejected) {
        if (!automaticCorrections) throw IntentPlans.reject("DIAGNOSTIC_REPAIR_DISABLED");
        return stepCall(input, session, callId, requests.repair(input, session, callId, diagnostic, rejected), "REPAIR");
    }

    /** One bounded read-only assessment in the same turn/plan ledger, without a correction loop. */
    public com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.AssessmentResponse assess(
            IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId, JsonNode candidate) {
        var request = requests.step(input, session, callId);
        var copy = request.<com.fasterxml.jackson.databind.node.ObjectNode>deepCopy();
        copy.withArray("loadResults").addObject().put("loaderId", "verification.candidate")
                .put("snapshotId", candidate.path("candidateDigest").asText())
                .put("asOf", java.time.Instant.now().toString()).put("completeness", "COMPLETE").putNull("nextCursor").set("data", candidate);
        if (copy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200_000)
            throw IntentPlans.reject("ASSESSMENT_CONTEXT_LIMIT");
        var result = validator.read("assessmentResponse", invoke(input, session, callId, "VERIFY", copy),
                com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.AssessmentResponse.class);
        var current = store.read(session.owner(), input.state().planId());
        if (current.revision() != input.token().revision() || current.status().equals("CANCELLED")) throw IntentPlans.conflict("MODEL_TURN_NO_LONGER_OWNED");
        return result;
    }
    private StepResponse stepCall(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId, JsonNode request, String phase) {
        int limit = requests.maxContextContinuations();
        if (limit < 0 || limit > 3) throw IntentPlans.reject("CONTEXT_CONTINUATION_LIMIT_INVALID");
        var work = new ContextWork(callId, request);
        graphRunner.run("call", java.util.List.of(
                new AgentGraphRunner.Node("call", () -> {
                    if (!work.request.path("callId").asText().equals(work.call.toString())
                            || !work.request.path("planId").asText().equals(input.state().planId().toString())) {
                        throw IntentPlans.reject("STEP_CALL_IDENTITY_CHANGED");
                    }
                    work.response = validator.read("stepResponse", invoke(input, session, work.call, phase, work.request), StepResponse.class);
                    if (!(work.response instanceof com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponseNeedContext) || limit == 0) {
                        return AgentGraphRunner.END;
                    }
                    if (work.round >= limit) throw IntentPlans.reject("CONTEXT_CONTINUATION_LIMIT_REACHED");
                    return "context";
                }, Set.of("context", AgentGraphRunner.END)),
                new AgentGraphRunner.Node("context", () -> {
                    var current = store.read(session.owner(), input.state().planId());
                    if (current.revision() != input.token().revision() || current.status().equals("CANCELLED")) {
                        throw IntentPlans.conflict("MODEL_TURN_NO_LONGER_OWNED");
                    }
                    work.call = UUID.nameUUIDFromBytes((callId + ":context:" + (++work.round)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var need = (com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponseNeedContext) work.response;
                    work.request = requests.continueStep(input, session, work.call, work.request, need.requests());
                    return "call";
                }, Set.of("call"))), input.state().planId() + ":context:" + callId, AgentGraphRunner.inTurn());
        return work.response;
    }

    private static final class ContextWork {
        private UUID call;
        private JsonNode request;
        private StepResponse response;
        private int round;

        private ContextWork(UUID call, JsonNode request) {
            this.call = call;
            this.request = request;
        }
    }

    private JsonNode invoke(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId, String phase, JsonNode request) {
        var current = store.read(session.owner(), input.state().planId());
        if (current.revision() != input.token().revision() || current.status().equals("CANCELLED")) throw IntentPlans.conflict("MODEL_TURN_NO_LONGER_OWNED");
        if (!request.path("messageId").asText().equals(input.currentMessage().messageId().toString())
                || !request.path("message").asText().equals(input.currentMessage().text())) throw IntentPlans.reject("CURRENT_INPUT_MISSING_OR_CHANGED");
        JsonNode dialogue = phase.equals("PLANNER") ? request.path("dialogue") : request.at("/context/dialogue");
        if (input.token().answeredQuestion() != null) {
            var question = dialogue.path("pendingQuestion");
            var expected = input.token().answeredQuestion();
            var actual = validator.read("pendingQuestion", question, com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PendingQuestion.class);
            if (!expected.equals(actual)) throw IntentPlans.reject("EXACT_QUESTION_CONTEXT_MISSING");
        }
        var route = policies.route(input, session, phase);
        var submitted = validator.parse(store.request(session.owner(), input.state().planId(), input.token().requestId()));
        if (!route.policy().path("policyId").asText().equals(input.state().policyId()) || !route.policy().path("policyVersion").asText().equals(input.state().policyVersion())
                || !route.selection().phase().equals(phase)) throw IntentPlans.conflict("MODEL_POLICY_VERSION_CHANGED");
        if (submitted.path("kind").asText().equals("turn.submit")) {
            if (!submitted.at("/routing/mode").asText().equals(route.policy().path("mode").asText())
                    || submitted.at("/routing/mode").asText().equals("PINNED")
                    && !submitted.at("/routing/modelConfigId").asText().equals(route.policy().path("pinnedModelConfigId").asText())) throw IntentPlans.conflict("PINNED_OR_ROUTING_PREFERENCE_CHANGED");
        }
        var token = input.token();
        var turn = new AttemptLedger.Turn(session.owner(), token.conversationId(), token.planId(), token.requestId());
        var correlation = new AssistantTraceRecorder.Correlation(session.owner(), token.conversationId(), token.messageId(), token.requestId(), token.requestId());
        requests.verifyForDispatch(input, session, request);
        try {
            return calls.call(turn, callId, route.policy(), request, route.selection(), route.authorized(), route.capabilities(), route.availability(), correlation, route.adapter(), null, route.proposalCheck(),
                    prepared -> requests.verifyForDispatch(input, session, prepared));
        } catch (com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected rejected) {
            if (!automaticCorrections || Set.of("REPAIR", "VERIFY").contains(phase)) throw rejected;
            var latest = store.read(session.owner(), input.state().planId());
            if (latest.revision() != input.token().revision() || latest.status().equals("CANCELLED")) throw IntentPlans.conflict("MODEL_TURN_NO_LONGER_OWNED");
            requests.verifyForDispatch(input, session, request);
            UUID correctionId = UUID.nameUUIDFromBytes((callId + ":format-correction").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return calls.call(turn, correctionId, route.policy(), request, route.selection(), route.authorized(), route.capabilities(), route.availability(), correlation, route.adapter(), rejected, route.proposalCheck(),
                    prepared -> requests.verifyForDispatch(input, session, prepared));
        }
    }
}
