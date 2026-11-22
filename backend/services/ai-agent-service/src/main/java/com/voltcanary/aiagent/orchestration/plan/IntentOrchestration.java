package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PendingQuestion;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlannerResponse;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponse;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.TurnSubmit;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.service.PlanningInputGuard;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.conflict;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.reject;

/** Domain node handlers. The injected library runner owns traversal; generation stays opt-in. */
public final class IntentOrchestration {
    public record Runtime(ObjectMapper mapper, ProtocolValidator validator, Clock clock, Duration expiry,
            AssistantTraceRecorder traces, AgentGraphRunner runner, OrchestrationPorts.Consultation consultation) {
        public Runtime(ObjectMapper mapper, ProtocolValidator validator, Clock clock, Duration expiry,
                AssistantTraceRecorder traces, AgentGraphRunner runner) {
            this(mapper, validator, clock, expiry, traces, runner, (input, step, session, nameRequested) -> false);
        }
    }
    public record Skills(Set<String> authorized, Set<String> capabilities) {
        public Skills {
            authorized = Set.copyOf(authorized);
            capabilities = Set.copyOf(capabilities);
        }
    }
    private final IntentPlanStore store;
    private final IntentPlanGraph graph;
    private final OrchestrationPorts.Models models;
    private final OrchestrationPorts.CommandReviews compiler;
    private final OrchestrationPorts.Receipts receipts;
    private final IntentReviewLifecycle lifecycle;
    private final ConfirmedInputs inputs;
    private final Runtime runtime;

    public IntentOrchestration(IntentPlanStore store, IntentPlanGraph graph, OrchestrationPorts.Models models,
            OrchestrationPorts.CommandReviews compiler, OrchestrationPorts.Receipts receipts, Runtime runtime) {
        this.store = store;
        this.graph = graph;
        this.models = models;
        this.compiler = compiler;
        this.receipts = receipts;
        this.lifecycle = new IntentReviewLifecycle(receipts);
        this.inputs = new ConfirmedInputs(receipts);
        this.runtime = runtime;
    }

    public IntentPlans.State submit(OrchestrationPorts.Session session, UUID conversation, UUID planId, TurnSubmit request,
            String policyId, String policyVersion, Skills skills) {
        return submit(session, conversation, planId, request, policyId, policyVersion, skills, null);
    }

    public IntentPlans.State submit(OrchestrationPorts.Session session, UUID conversation, UUID planId, TurnSubmit request,
            String policyId, String policyVersion, Skills skills, Long conversationRevision) {
        runtime.validator().validate("turnSubmit", runtime.mapper().valueToTree(request));
        new PlanningInputGuard().validate(request.message(), Map.of());
        if (!request.routing().policyId().equals(policyId)) throw conflict("ROUTING_POLICY_CHANGED");
        var seed = planId == null ? seed(conversation, UUID.nameUUIDFromBytes((conversation + ":" + request.requestId()).getBytes(StandardCharsets.UTF_8)), policyId, policyVersion)
                : store.read(session.owner(), planId);
        if (!seed.conversationId().equals(conversation) || !seed.policyId().equals(policyId) || !seed.policyVersion().equals(policyVersion)) throw conflict("PLAN_POLICY_OR_CONVERSATION_CHANGED");
        requireEngineOrStoredReplay(seed, session, UUID.fromString(request.requestId()));
        var claim = store.begin(session.owner(), seed, UUID.fromString(request.requestId()), request.expectedRevision(), "TURN", json(request), request.message(), false, conversationRevision);
        if (!claim.claimed()) return claim.state();
        var work = new Work(claim.token(), claim.state(), session, skills);
        try (var trace = AssistantTrace.open(runtime.traces(), correlation(work)); var turn = AssistantTrace.start("TURN", Map.of("planId", seed.planId()))) {
            if (request.replyToQuestionId() != null && (work.token.answeredQuestion() == null
                    || !work.token.answeredQuestion().questionId().equals(request.replyToQuestionId()))) throw conflict("QUESTION_CHANGED");
            if (Set.of("CANCELLED", "EXPIRED", "SUCCEEDED").contains(work.state.status()) && !work.state.steps().isEmpty()) throw conflict("TERMINAL_PLAN_REQUIRES_NEW_PLAN");
            run(work, request);
            turn.success(Map.of("status", work.state.status()));
        } catch (RuntimeException failure) {
            fail(work, failure);
        }
        return work.state;
    }

    private String decide(Work work, PlannerResponse decision, boolean prepare) {
        ensureCurrent(work);
        runtime.validator().validate("plannerResponse", runtime.mapper().valueToTree(decision));
        switch (decision.decision()) {
            case "PLAN", "AMEND" -> {
                var previousReviews = work.state.reviews();
                work.state = graph.apply(work.state, decision, store.sourceMessages(work.session.owner(), work.state.conversationId()),
                        store.latestInput(work.session.owner(), work.state.planId()).messageId().toString(), true, work.skills.authorized(), work.skills.capabilities());
                previousReviews.forEach((id, review) -> {
                    if (!work.state.reviews().containsKey(id)) receipts.invalidate(review, work.session);
                });
                if (prepare) {
                    var ready = work.state.steps().stream().filter(step -> step.status().equals("READY")).findFirst();
                    if (ready.isPresent()) {
                        work.selectedStep = ready.get().stepId();
                        return "prepare";
                    }
                }
                work.reply = decision.explanation();
            }
            case "CONTINUE" -> {
                var step = work.state.step(decision.targetStepId());
                if (!Set.of("READY", "WAITING_INPUT", "WAITING_CONFIRMATION").contains(step.status())) throw reject("STEP_NOT_CONTINUABLE");
                if (prepare) {
                    work.selectedStep = step.stepId();
                    return "prepare";
                }
                work.reply = decision.explanation();
            }
            case "ASK" -> {
                if (!decision.proposedGoals().isEmpty()) {
                    var goals = new ArrayList<>(work.state.goals());
                    var sources = store.sourceMessages(work.session.owner(), work.state.conversationId());
                    for (var goal : decision.proposedGoals()) {
                        if (!sources.contains(goal.sourceMessageId()) || goals.stream().anyMatch(old -> old.goalId().equals(goal.goalId()))) throw reject("GOAL_PROVENANCE_OR_ID");
                        goals.add(new AssistantProtocol.Goal(goal.goalId(), goal.sourceMessageId(), goal.text(), "PENDING", null));
                    }
                    work.state = work.state.revise(work.state.revision(), work.state.status(), work.state.steps(), goals, work.state.facts(), work.state.pendingQuestion(),
                            work.state.focus(), work.state.skills(), work.state.reviews(), work.state.outputs());
                }
                work.state = work.state.revise(work.state.revision(), "WAITING_INPUT", work.state.steps(), work.state.goals(), work.state.facts(),
                        question(work, null, decision.question()), work.state.focus(), work.state.skills(), work.state.reviews(), work.state.outputs());
                work.reply = decision.question().text();
            }
            case "UNSUPPORTED" -> {
                work.state = work.state.revise(work.state.revision(), "FAILED", work.state.steps(), work.state.goals(), work.state.facts(), null,
                        work.state.focus(), work.state.skills(), work.state.reviews(), work.state.outputs());
                work.reply = decision.explanation();
            }
            default -> throw reject("UNKNOWN_PLANNER_DECISION");
        }
        return "finish";
    }

    private String prepare(Work work) {
        String stepId = work.selectedStep;
        // Keep the accepted immutable review while preparing a candidate. PREPARING/FAILED
        // cannot be confirmed; the durable store retires a replaced review atomically.
        var resolved = new java.util.LinkedHashMap<>(inputs.resolve(work.state, stepId, work.session));
        boolean refinement = work.state.reviews().containsKey(stepId)
                || runtime.validator().parse(store.request(work.session.owner(), work.state.planId(), work.token.requestId())).hasNonNull("selectedReviewId");
        if (refinement) {
            // Initial planner literals are proposals, not current values of an accepted review.
            // Receipt/fact and dependency bindings remain mandatory and are never discarded.
            for (var parameter : work.state.step(stepId).inputs()) {
                if (parameter.binding() instanceof AssistantProtocol.BindingLiteral) resolved.remove(parameter.field());
            }
        }
        var old = work.state.step(stepId);
        work.state = IntentPlans.replace(work.state, IntentPlans.step(old, "PREPARING", old.reviewId(), old.receiptId(), null), "ACTIVE", work.state.pendingQuestion()).contextRequests(List.of());
        work.resolved = Map.copyOf(resolved);
        return "step";
    }
    private String acceptStep(Work work) {
        String stepId = work.selectedStep;
        var old = work.state.step(stepId);
        var response = work.response;
        if (response instanceof AssistantProtocol.StepResponseReplanRequired) {
            if (!work.allowReplan || work.repaired) throw reject("REPLAN_LIMIT_REACHED");
            work.state = IntentPlans.replace(work.state, IntentPlans.step(work.state.step(stepId), "WAITING_INPUT", old.reviewId(), old.receiptId(), null), "ACTIVE", work.state.pendingQuestion());
            work.replanning = true;
            work.allowReplan = false;
            return "plan";
        } else if (response instanceof AssistantProtocol.StepResponseQuestion asked) {
            if ("CREATE_DASHBOARD".equals(old.intentId()) && old.reviewId() == null
                    && asked.question().missingFields().equals(List.of("name")) && asked.question().choices().isEmpty()
                    && runtime.consultation().run(modelInput(work, work.resolved), stepId, work.session, true)) {
                work.reply = asked.question().text();
                return "finish";
            }
            var pending = question(work, stepId, asked.question());
            work.state = IntentPlans.replace(work.state, IntentPlans.step(work.state.step(stepId), "WAITING_INPUT", old.reviewId(), old.receiptId(), null), "WAITING_INPUT", pending);
            work.reply = asked.question().text();
        } else if (response instanceof AssistantProtocol.StepResponsePrepared prepared) {
            return "compile";
        } else if (response instanceof AssistantProtocol.StepResponseNeedContext context) {
            var skill = graph.skill(work.state, stepId, work.skills.authorized(), work.skills.capabilities());
            if (context.requests().stream().anyMatch(request -> !skill.loaderIds().contains(request.loaderId()))) throw reject("LOADER_UNAVAILABLE");
            work.state = IntentPlans.replace(work.state, IntentPlans.step(work.state.step(stepId), "WAITING_DATA", old.reviewId(), old.receiptId(), null), "WAITING_DATA", null).contextRequests(context.requests());
            work.reply = context.explanation();
        } else if (response instanceof AssistantProtocol.StepResponseNoChange noChange) {
            var diagnostic = new AssistantProtocol.Diagnostic("OUTCOME", "POSTCONDITIONS_REQUIRED", "Ожидается серверная проверка результата", List.of(), false);
            work.state = IntentPlans.replace(work.state, IntentPlans.step(work.state.step(stepId), "WAITING_DATA", old.reviewId(), old.receiptId(), diagnostic), "WAITING_DATA", null);
            work.reply = noChange.explanation();
        } else if (response instanceof AssistantProtocol.StepResponseUnsupported unsupported) {
            work.state = IntentPlans.replace(work.state, IntentPlans.step(work.state.step(stepId), "FAILED", old.reviewId(), old.receiptId(), unsupported.diagnostic()), "FAILED", null);
            work.reply = unsupported.explanation();
        }
        return "finish";
    }

    private void run(Work work, TurnSubmit request) {
        var nodes = List.of(
                node(work, "authorize", () -> {
                    importResources(work);
                    for (var fact : work.state.facts()) if (fact.output() != null) receipts.output(fact.output(), work.session);
                    for (var review : work.state.reviews().values()) receipts.inspect(review, work.session);
                    if (request.continueStepId() != null) {
                        var step = work.state.steps().stream().filter(item -> item.stepId().equals(request.continueStepId())).findFirst();
                        if (request.selectedReviewId() != null || work.state.pendingQuestion() != null || step.isEmpty()
                                || !step.get().status().equals("READY") || work.state.reviews().containsKey(request.continueStepId())
                                || step.get().dependsOn().stream().anyMatch(id -> !work.state.step(id).status().equals("SUCCEEDED"))) {
                            throw conflict("STEP_NOT_CONTINUABLE");
                        }
                        work.selectedStep = request.continueStepId();
                        return "prepare";
                    }
                    var answered = work.token.answeredQuestion();
                    if (request.replyToQuestionId() != null && answered != null && answered.stepId() != null
                            && work.state.step(answered.stepId()).status().equals("WAITING_INPUT")) {
                        work.selectedStep = answered.stepId();
                        work.allowReplan = true;
                        return "prepare";
                    }
                    return "plan";
                }, "prepare", "plan"),
                node(work, "plan", () -> decide(work, models.plan(modelInput(work, Map.of()), work.session,
                        work.replanning ? work.token.replanCallId() : work.token.primaryCallId()), !work.replanning), "prepare", "finish"),
                node(work, "prepare", () -> {
                    String route = prepare(work);
                    var step = work.state.step(work.selectedStep);
                    if (step.intentId().equals("CREATE_DASHBOARD") && !work.state.reviews().containsKey(step.stepId())) {
                        var names = request.selection().stream().filter(item -> "name".equals(item.field())).toList();
                        if (names.size() > 1) throw reject("AMBIGUOUS_DASHBOARD_NAME");
                        if (!names.isEmpty()) {
                            String name = names.getFirst().ref();
                            if (name.isBlank() || name.length() > 80) throw reject("REQUIREMENT_NAME_INVALID");
                            var resolved = new java.util.LinkedHashMap<>(work.resolved);
                            var supplied = runtime.mapper().getNodeFactory().textNode(name);
                            var bound = resolved.putIfAbsent("name", supplied);
                            if (bound != null && !supplied.equals(bound)) throw conflict("DASHBOARD_NAME_BINDING_CHANGED");
                            work.resolved = Map.copyOf(resolved);
                        }
                    }
                    return route;
                }, "step"),
                node(work, "step", () -> {
                    if (runtime.consultation().run(modelInput(work, work.resolved), work.selectedStep, work.session, false)) {
                        work.reply = "Специалисту нужно уточнение.";
                        return "finish";
                    }
                    ensureCurrent(work);
                    work.response = models.step(modelInput(work, work.resolved), work.session,
                            stable(work.token.requestId(), "step:" + work.selectedStep));
                    ensureCurrent(work);
                    graph.validateStepResponse(work.response);
                    return "accept";
                }, "accept", "finish"),
                node(work, "accept", () -> acceptStep(work), "plan", "compile", "finish"),
                node(work, "compile", () -> compile(work), "repair", "finish"),
                node(work, "repair", () -> {
                    work.repaired = true;
                    var rejected = (AssistantProtocol.StepResponsePrepared) work.response;
                    work.response = models.repair(modelInput(work, work.resolved), work.session,
                            stable(work.token.requestId(), "repair:" + work.selectedStep), work.preparationFailure.diagnostic(), rejected.command());
                    ensureCurrent(work);
                    graph.validateStepResponse(work.response);
                    return "accept";
                }, "accept"),
                node(work, "finish", () -> AgentGraphRunner.END, AgentGraphRunner.END));
        runtime.runner().run("authorize", nodes, work.state.planId() + ":" + work.token.requestId(), new AgentGraphRunner.Persistence() {
            @Override
            public java.util.Optional<AgentGraphRunner.Checkpoint> load() {
                var saved = work.state.execution();
                if (!work.token.requestId().toString().equals(saved.requestId())) return java.util.Optional.empty();
                return java.util.Optional.of(new AgentGraphRunner.Checkpoint(saved.checkpointId(), saved.node(), saved.next(), saved.values()));
            }

            @Override
            public void save(AgentGraphRunner.Checkpoint position) {
                ensureCurrent(work);
                work.state = work.state.execution(new IntentPlans.GraphExecution(runtime.runner().engine(), runtime.runner().version(),
                        work.token.requestId().toString(), position.id(), position.node(), position.next(), position.values()));
                if (position.next().equals(AgentGraphRunner.END)) finish(work, work.reply);
                else checkpoint(work);
            }
        });
        if (!work.finished) throw reject("GRAPH_TURN_NOT_COMMITTED");
    }

    private AgentGraphRunner.Node node(Work work, String name, java.util.function.Supplier<String> action, String... targets) {
        return new AgentGraphRunner.Node(name, () -> {
            ensureCurrent(work);
            return action.get();
        }, Set.of(targets));
    }

    private String compile(Work work) {
        var prepared = (AssistantProtocol.StepResponsePrepared) work.response;
        var skill = graph.skill(work.state, work.selectedStep, work.skills.authorized(), work.skills.capabilities());
        graph.validateCommand(skill, prepared.command(), work.skills.authorized(), work.skills.capabilities());
        OrchestrationPorts.Prepared review;
        try {
            review = compiler.prepare(modelInput(work, work.resolved), skill, prepared.command(), work.session);
        } catch (StepPreparationFailure failure) {
            if (work.repaired || !models.automaticCorrections() || !failure.diagnostic().retriable()) throw failure;
            var diagnostic = failure.diagnostic();
            try {
                runtime.traces().append(correlation(work), stable(work.token.requestId(), "step:" + work.selectedStep),
                        "PREPARATION", "FAILED", diagnostic.code(), null,
                        Map.of("stepId", work.selectedStep, "category", diagnostic.category(), "recoveryScheduled", true));
            } catch (RuntimeException traceFailure) {
                // Trace availability does not authorize a replay or change the recovery decision.
            }
            work.preparationFailure = failure;
            return "repair";
        }
        ensureCurrent(work);
        work.state = lifecycle.attach(work.state, work.selectedStep, skill, review, work.session);
        work.reply = prepared.explanation();
        return "finish";
    }

    private void requireEngine(IntentPlans.State state) {
        var execution = state.execution();
        if (execution == null) throw conflict("GRAPH_ENGINE_MIGRATION_REQUIRED");
        if (!runtime.runner().engine().equals(execution.engine()) || !runtime.runner().version().equals(execution.definition())) {
            throw conflict("GRAPH_ENGINE_VERSION_CHANGED");
        }
    }

    private void requireEngineOrStoredReplay(IntentPlans.State state, OrchestrationPorts.Session session, UUID requestId) {
        try {
            requireEngine(state);
        } catch (IntentPlans.Rejected incompatible) {
            // begin still checks the exact stored request digest and can only replay it.
            // A different engine must not block model-free reads of an existing request.
            try {
                store.request(session.owner(), state.planId(), requestId);
            } catch (IntentPlans.Rejected missing) {
                if (missing.status() == 404) throw incompatible;
                throw missing;
            }
        }
    }

    private void importResources(Work work) {
        var events = store.resources(work.session.owner(), work.state.conversationId(), 0, 100);
        if (events.size() == 100 && !store.resources(work.session.owner(), work.state.conversationId(), events.getLast().sequence(), 1).isEmpty()) throw reject("RESOURCE_CONTEXT_REQUIRES_NARROWING");
        var facts = new ArrayList<>(work.state.facts());
        for (var event : events) if (facts.stream().noneMatch(item -> item.fact().factId().equals(event.factId()))) {
            facts.add(inputs.fact(event.output(), event.factId(), event.sourceMessageId().toString(), work.session));
        }
        var focus = work.state.focus();
        if (focus.origin().equals("NONE") && !events.isEmpty()) {
            String device = null;
            String dashboard = null;
            for (var event : events) {
                if ("device".equals(event.output().resourceKind())) device = event.factId();
                if ("dashboard".equals(event.output().resourceKind())) dashboard = event.factId();
            }
            if (device != null || dashboard != null) focus = new Focus(device, dashboard, null, null, "RECENT_RESULT");
        }
        work.state = work.state.revise(work.state.revision(), work.state.status(), work.state.steps(), work.state.goals(), facts,
                work.state.pendingQuestion(), focus, work.state.skills(), work.state.reviews(), work.state.outputs());
    }

    private IntentPlans.ModelInput modelInput(Work work, Map<String, com.fasterxml.jackson.databind.JsonNode> resolved) {
        return new IntentPlans.ModelInput(work.token, work.state, resolved, store.latestInput(work.session.owner(), work.state.planId()));
    }
    private PendingQuestion question(Work work, String step, AssistantProtocol.Question question) {
        return new PendingQuestion(stable(work.token.requestId(), "question").toString(), stable(work.token.requestId(), "REPLY").toString(), step,
                work.state.revision() + 1, question);
    }
    private IntentPlans.State seed(UUID conversation, UUID id, String policy, String policyVersion) {
        return new IntentPlans.State(id, conversation, 0, "ACTIVE", List.of(), List.of(), List.of(), null, new Focus(null, null, null, null, "NONE"),
                Map.of(), Map.of(), Map.of(), policy, policyVersion, runtime.clock().instant().plus(runtime.expiry()), List.of(),
                new IntentPlans.GraphExecution(runtime.runner().engine(), runtime.runner().version(), null, null, "idle", "authorize", Map.of()));
    }
    private void checkpoint(Work work) {
        work.state = store.commit(work.token, work.state, null, false);
        work.token = token(work.token, work.state.revision());
    }
    private void ensureCurrent(Work work) {
        var state = store.read(work.session.owner(), work.state.planId());
        if (state.revision() != work.token.revision() || state.status().equals("CANCELLED")) throw conflict("LATE_OR_CANCELLED_MODEL_RESULT");
    }
    private void finish(Work work, String reply) {
        if (reply == null) throw reject("GRAPH_REPLY_MISSING");
        work.state = store.commit(work.token, work.state, reply, true);
        work.finished = true;
    }
    private void fail(Work work, RuntimeException failure) {
        if (work.finished) throw failure;
        if (failure instanceof IntentPlans.Rejected rejected && rejected.status() == 409) {
            var current = store.read(work.session.owner(), work.state.planId());
            if (current.revision() == work.token.revision()) work.state = store.commit(work.token, current, "Запрос отклонён: состояние или подтверждение изменилось", true);
            else work.state = current;
            throw rejected;
        }
        boolean budgetStop = failure instanceof com.voltcanary.aiagent.orchestration.routing.ModelRouting.Stop;
        String code = failure instanceof StepPreparationFailure preparation ? preparation.diagnostic().code() : failure instanceof IntentPlans.Rejected || budgetStop ? failure.getMessage() : "PREPARATION_FAILED";
        if (failure instanceof IllegalArgumentException && failure.getMessage() != null && failure.getMessage().matches("[A-Z0-9_:.]{1,160}")) code = failure.getMessage();
        try {
            runtime.traces().append(correlation(work), work.token.primaryCallId(), "PREPARATION", "FAILED", code, null,
                    Map.of("failureType", failure.getClass().getSimpleName(), "locations", java.util.Arrays.stream(failure.getStackTrace())
                            .filter(frame -> frame.getClassName().startsWith("com.voltcanary." )).limit(5)
                            .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber()).toList()));
        } catch (RuntimeException diagnosticFailure) { /* A diagnostic failure must never replay provider or domain work. */ }
        String category = failure instanceof StepPreparationFailure preparation ? preparation.diagnostic().category()
                : code.startsWith("PROVIDER_") ? "PROVIDER"
                : failure instanceof com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected ? "MODEL_FORMAT"
                : budgetStop ? "BUDGET" : code.equals("CONTEXT_DEPENDENCY_UNAVAILABLE") ? "DEPENDENCY" : code.startsWith("CONTEXT_") ? "CONTEXT" : "INVALID_PLAN";
        var diagnostic = failure instanceof StepPreparationFailure preparation ? preparation.diagnostic()
                : new AssistantProtocol.Diagnostic(category, code, "Запрос сохранён; автоматический повтор не выполнялся", List.of(), false);
        if (work.selectedStep != null) work.state = IntentPlans.replace(work.state,
                IntentPlans.step(work.state.step(work.selectedStep), "FAILED", work.state.step(work.selectedStep).reviewId(), work.state.step(work.selectedStep).receiptId(), diagnostic), "FAILED", work.state.pendingQuestion());
        else work.state = work.state.revise(work.state.revision(), "FAILED", work.state.steps(), work.state.goals(), work.state.facts(), work.state.pendingQuestion(),
                work.state.focus(), work.state.skills(), work.state.reviews(), work.state.outputs());
        finish(work, diagnostic.message() + " (" + code + ")");
    }
    private AssistantTraceRecorder.Correlation correlation(Work work) {
        return new AssistantTraceRecorder.Correlation(work.session.owner(), work.state.conversationId(), work.token.messageId(), work.token.requestId(), work.token.requestId());
    }
    private String json(Object value) {
        try { return runtime.mapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw reject("REQUEST_ENCODING"); }
    }
    private static UUID stable(UUID request, String suffix) { return UUID.nameUUIDFromBytes((request + ":" + suffix).getBytes(StandardCharsets.UTF_8)); }
    private static IntentPlans.Token token(IntentPlans.Token old, long revision) {
        return new IntentPlans.Token(old.owner(), old.conversationId(), old.planId(), old.requestId(), old.messageId(), revision,
                old.primaryCallId(), old.replanCallId(), old.message(), old.answeredQuestion());
    }
    private static final class Work {
        private IntentPlans.Token token;
        private IntentPlans.State state;
        private final OrchestrationPorts.Session session;
        private final Skills skills;
        private String selectedStep;
        private boolean finished;
        private String reply;
        private boolean allowReplan;
        private boolean replanning;
        private boolean repaired;
        private Map<String, com.fasterxml.jackson.databind.JsonNode> resolved = Map.of();
        private StepResponse response;
        private StepPreparationFailure preparationFailure;
        private Work(IntentPlans.Token token, IntentPlans.State state, OrchestrationPorts.Session session, Skills skills) {
            this.token = token;
            this.state = state;
            this.session = session;
            this.skills = skills;
        }
    }
}
