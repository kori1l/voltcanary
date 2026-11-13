package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.ConfirmationRequest;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.ControlRequest;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.conflict;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.reject;

/** Typed user controls use no language routing, model call or independent retry budget. */
public final class IntentPlanActions {
    private final IntentPlanStore store;
    private final OrchestrationPorts.Receipts receipts;
    private final IntentReviewLifecycle lifecycle;
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final AssistantTraceRecorder traces;
    private final com.voltcanary.aiagent.orchestration.routing.AttemptLedger ledger;
    private final AgentGraphRunner runner;

    public IntentPlanActions(IntentPlanStore store, OrchestrationPorts.Receipts receipts, ObjectMapper mapper,
            ProtocolValidator validator, AssistantTraceRecorder traces, com.voltcanary.aiagent.orchestration.routing.AttemptLedger ledger,
            AgentGraphRunner runner) {
        this.store = store;
        this.receipts = receipts;
        this.lifecycle = new IntentReviewLifecycle(receipts);
        this.mapper = mapper;
        this.validator = validator;
        this.traces = traces;
        this.ledger = ledger;
        this.runner = runner;
    }

    public IntentPlans.State confirm(OrchestrationPorts.Session session, UUID planId, ConfirmationRequest request) {
        validator.validate("confirmationRequest", mapper.valueToTree(request));
        var state = store.read(session.owner(), planId);
        var claim = store.begin(session.owner(), state, UUID.fromString(request.requestId()), request.expectedRevision(), "CONFIRMATION", json(request), "Подтверждён review " + request.reviewId(), false);
        if (!claim.claimed()) return claim.state();
        var work = new ConfirmationWork(claim.token(), claim.state());
        try (var trace = AssistantTrace.open(traces, correlation(work.token)); var span = AssistantTrace.start("EXECUTION", java.util.Map.of("reviewId", request.reviewId(), "stepId", request.stepId()))) {
            requireEngine(work.state);
            confirmationGraph(work, session, request);
            span.success(java.util.Map.of("status", work.state.status()));
            return work.state;
        } catch (RuntimeException failure) {
            var current = store.read(session.owner(), planId);
            if (current.revision() != work.token.revision()) throw conflict("LATE_EXECUTION_RESULT_REQUIRES_RECONCILIATION");
            if (work.dispatched) {
                var step = current.step(request.stepId());
                state = IntentPlans.replace(current, IntentPlans.step(step, "UNKNOWN", step.reviewId(), step.receiptId(),
                        new Diagnostic("EXECUTION", "RECEIPT_RECONCILIATION_REQUIRED", "Сверьте сохранённый receipt", List.of(), false)), "FAILED", null);
                return store.commit(work.token, state, "Результат неизвестен; повтор исполнения не запускался", true);
            }
            store.commit(work.token, current, "Подтверждение отклонено: review или доступ изменился", true);
            throw failure;
        }
    }

    private void requireEngine(IntentPlans.State state) {
        // Saved pre-graph reviews remain executable under their original explicit confirmation.
        if (state.execution() != null && (!runner.engine().equals(state.execution().engine())
                || !runner.version().equals(state.execution().definition()))) throw conflict("GRAPH_ENGINE_VERSION_CHANGED");
    }

    private void confirmationGraph(ConfirmationWork work, OrchestrationPorts.Session session, ConfirmationRequest request) {
        var nodes = List.of(
                new AgentGraphRunner.Node("validate_confirmation", () -> {
                    var step = work.state.step(request.stepId());
                    work.review = work.state.reviews().get(step.stepId());
                    if (!step.status().equals("WAITING_CONFIRMATION") || work.review == null
                            || !work.review.reviewId().toString().equals(request.reviewId())
                            || work.review.version() != request.expectedVersion() || !work.review.digest().equals(request.payloadDigest())) {
                        throw conflict("REVIEW_CONFIRMATION_CHANGED");
                    }
                    new ConfirmedInputs(receipts).resolve(work.state, step.stepId(), session);
                    if (!receipts.inspect(work.review, session).status().equals("READY")) throw conflict("REVIEW_NOT_READY");
                    return "dispatch";
                }, Set.of("dispatch")),
                new AgentGraphRunner.Node("dispatch", () -> {
                    var step = work.state.step(request.stepId());
                    work.state = IntentPlans.replace(work.state, IntentPlans.step(step, "EXECUTING", step.reviewId(), null, null), "ACTIVE", null);
                    return "execute";
                }, Set.of("execute")),
                new AgentGraphRunner.Node("execute", () -> {
                    work.dispatched = true;
                    receipts.execute(work.review, session);
                    return "readback";
                }, Set.of("readback")),
                new AgentGraphRunner.Node("readback", () -> {
                    work.state = lifecycle.collect(work.state, request.stepId(), session);
                    return "confirmed";
                }, Set.of("confirmed")),
                new AgentGraphRunner.Node("confirmed", () -> AgentGraphRunner.END, Set.of(AgentGraphRunner.END)));
        runner.run("validate_confirmation", nodes, work.state.planId() + ":" + work.token.requestId(), new AgentGraphRunner.Persistence() {
            @Override
            public java.util.Optional<AgentGraphRunner.Checkpoint> load() { return java.util.Optional.empty(); }

            @Override
            public void save(AgentGraphRunner.Checkpoint position) {
                var current = store.read(session.owner(), work.state.planId());
                if (current.revision() != work.token.revision() || current.status().equals("CANCELLED")) {
                    throw conflict("LATE_EXECUTION_RESULT_REQUIRES_RECONCILIATION");
                }
                if (work.state.execution() != null) {
                    work.state = work.state.execution(new IntentPlans.GraphExecution(runner.engine(), runner.version(), work.token.requestId().toString(),
                            position.id(), position.node(), position.next(), position.values()));
                }
                boolean terminal = position.next().equals(AgentGraphRunner.END);
                work.state = store.commit(work.token, work.state,
                        terminal ? "Результат шага: " + work.state.step(request.stepId()).status() : null, terminal);
                work.token = token(work.token, work.state.revision());
            }
        });
    }

    private static final class ConfirmationWork {
        private IntentPlans.Token token;
        private IntentPlans.State state;
        private IntentPlans.Review review;
        private boolean dispatched;

        private ConfirmationWork(IntentPlans.Token token, IntentPlans.State state) {
            this.token = token;
            this.state = state;
        }
    }

    public IntentPlans.State control(OrchestrationPorts.Session session, UUID planId, ControlRequest request) {
        validator.validate("controlRequest", mapper.valueToTree(request));
        var current = store.read(session.owner(), planId);
        var claim = store.begin(session.owner(), current, UUID.fromString(request.requestId()), request.expectedRevision(), "CONTROL", json(request),
                "Управление планом: " + request.action(), request.action().equals("CANCEL"));
        if (!claim.claimed()) return claim.state();
        var state = claim.state();
        try (var trace = AssistantTrace.open(traces, correlation(claim.token())); var span = AssistantTrace.start("CONTROL", java.util.Map.of("action", request.action()))) {
            if (request.action().equals("CANCEL")) state = cancel(state, session);
            else if (request.action().equals("RETRY")) state = retry(state, request.stepId(), session);
            else if (request.action().equals("RESUME")) {
                if (!Set.of("PAUSED", "ACTIVE", "WAITING_DATA").contains(state.status())) throw conflict("PLAN_NOT_RESUMABLE");
                state = state.revise(state.revision(), "ACTIVE", IntentPlanGraph.refresh(state.steps()), state.goals(), state.facts(), state.pendingQuestion(),
                        state.focus(), state.skills(), state.reviews(), state.outputs());
            } else throw reject("CONTROL_UNSUPPORTED");
            state = store.commit(claim.token(), state, "Состояние плана: " + state.status(), true);
            if (request.action().equals("CANCEL")) {
                try {
                    ledger.cancel(new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Turn(session.owner(), state.conversationId(), state.planId(), claim.token().requestId()));
                } catch (com.voltcanary.aiagent.orchestration.routing.ModelRouting.Stop unavailable) {
                    if (!unavailable.getMessage().equals("BUDGET_UNAVAILABLE")) throw unavailable; // No provider attempt exists yet.
                }
            }
            span.success(java.util.Map.of("status", state.status()));
            return state;
        } catch (RuntimeException failure) {
            if (store.read(session.owner(), planId).revision() == claim.token().revision()) store.commit(claim.token(), claim.state(), "Управление отклонено; сохранённые результаты доступны", true);
            throw failure;
        }
    }

    private IntentPlans.State cancel(IntentPlans.State state, OrchestrationPorts.Session session) {
        var reviews = new HashMap<>(state.reviews());
        var steps = state.steps().stream().map(step -> {
            if (Set.of("SUCCEEDED", "SUPERSEDED", "UNKNOWN", "CANCELLED").contains(step.status())) return step;
            if (step.status().equals("EXECUTING")) return IntentPlans.step(step, "UNKNOWN", step.reviewId(), step.receiptId(),
                    new Diagnostic("EXECUTION", "CANCELLED_IN_FLIGHT", "Сверьте сохранённый receipt", List.of(), false));
            var review = reviews.remove(step.stepId());
            if (review != null) receipts.invalidate(review, session);
            return IntentPlans.step(step, "CANCELLED", null, step.receiptId(), null);
        }).toList();
        return state.revise(state.revision(), "CANCELLED", steps, state.goals(), state.facts(), null, state.focus(), state.skills(), reviews, state.outputs());
    }

    private IntentPlans.State retry(IntentPlans.State state, String stepId, OrchestrationPorts.Session session) {
        if (stepId == null) throw reject("RETRY_STEP_REQUIRED");
        var step = state.step(stepId);
        if (Set.of("UNKNOWN", "EXECUTING").contains(step.status())) return lifecycle.reconcile(state, stepId, session);
        if (state.status().equals("CANCELLED") || !Set.of("FAILED", "PREPARING").contains(step.status())) throw conflict("STEP_NOT_RETRYABLE");
        var review = state.reviews().get(stepId);
        if (review != null && receipts.inspect(review, session).actions().values().stream().anyMatch(action -> action.status().equals("SUCCESS"))) throw conflict("PARTIAL_REVIEW_REQUIRES_AMEND");
        var reviews = new HashMap<>(state.reviews());
        if (review != null) receipts.invalidate(review, session);
        reviews.remove(stepId);
        var next = IntentPlans.replace(state, IntentPlans.step(step, "BLOCKED", null, step.receiptId(), null), "ACTIVE", null);
        return next.revise(next.revision(), next.status(), IntentPlanGraph.refresh(next.steps()), next.goals(), next.facts(), null,
                next.focus(), next.skills(), reviews, next.outputs());
    }

    private AssistantTraceRecorder.Correlation correlation(IntentPlans.Token token) {
        return new AssistantTraceRecorder.Correlation(token.owner(), token.conversationId(), token.messageId(), token.requestId(), token.requestId());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw reject("REQUEST_ENCODING"); }
    }
    private static IntentPlans.Token token(IntentPlans.Token old, long revision) {
        return new IntentPlans.Token(old.owner(), old.conversationId(), old.planId(), old.requestId(), old.messageId(), revision,
                old.primaryCallId(), old.replanCallId(), old.message(), old.answeredQuestion());
    }
}
