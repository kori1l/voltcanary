package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.ConfirmedInputs;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntentPlanningSafetyScenarioTest {

    @Test
    void invalidPlannerReferencesReceiveOneCorrectionInsideTheOriginalBudget() throws Exception {
        var f = new IntentScenarioSupport();
        var plannerCalls = new AtomicInteger();
        var network = new AtomicInteger();
        var rejectCorrection = new java.util.concurrent.atomic.AtomicBoolean();
        var limits = new AssistantProtocol.Limits(
            6L,
            4_000_000L,
            10_000L,
            60_000L,
            100_000L,
            "USD"
        );
        var candidate = new AssistantProtocol.Candidate(
            "fixture",
            "model",
            "qualified",
            1_000_000L,
            900_000L,
            1000L,
            "JSON_TEXT",
            null
        );
        var policy = new AssistantProtocol.RoutingPolicy(
            "assistant-v2",
            "routing.policy",
            "policy",
            "1",
            "AUTO",
            null,
            List.of(
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "planner",
                    List.of(),
                    List.of("PLANNER"),
                    List.of(candidate),
                    false
                ),
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "economical",
                    List.of("CREATE_DEVICE"),
                    List.of("STEP"),
                    List.of(candidate),
                    false
                )
            ),
            limits,
            limits,
            "RESERVED_UPPER_BOUND",
            500L,
            BigDecimal.ONE,
            null
        );
        var history = new com.voltcanary.aiagent.adapter.JdbcContextHistory(f.jdbc, f.clock);
        var loading = new com.voltcanary.aiagent.orchestration.context.ContextLoading(
            f.registry,
            f.validator,
            new com.voltcanary.aiagent.adapter.JdbcContextCursors(f.jdbc),
            List.of(),
            f.clock
        );
        var requests = new com.voltcanary.aiagent.orchestration.context.BoundedSkillRequests(
            f.mapper,
            f.validator,
            f.registry,
            f.store,
            history,
            loading,
            (input, session) -> f.skills,
            f.receipts,
            new com.voltcanary.aiagent.adapter.ScopedReviewContext(
                f.reviews,
                f.store,
                f.contexts,
                f.validator,
                f.mapper
            ),
            f.clock,
            100_000
        );
        var adapter = new RoutedModelCalls.WireAdapter() {
            public String serialize(JsonNode request, JsonNode schema, String mode) {
                return request.toString();
            }

            public String serializeCorrection(
                JsonNode request,
                JsonNode schema,
                String mode,
                com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected rejected
            ) {
                assertTrue(rejected.diagnostic().contains("UNKNOWN_FACT"));
                var body = request.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put(
                    "correction",
                    rejected.diagnostic()
                );
                return body.toString();
            }

            public BudgetedModelCalls.Reply send(
                String body,
                ModelRouting.Route route,
                long timeout
            ) throws Exception {
                network.incrementAndGet();
                var request = f.mapper.readTree(body);
                Object response;
                if (request.path("kind").asText().equals("planner.request")) {
                    int call = plannerCalls.incrementAndGet();
                    AssistantProtocol.Binding binding =
                        call % 2 == 1 || rejectCorrection.get()
                            ? new AssistantProtocol.BindingFactRef(
                                  "fact_ref",
                                  "selected-choice-is-not-a-fact"
                              )
                            : new AssistantProtocol.BindingLiteral(
                                  "literal",
                                  f.mapper.valueToTree("Reviewed")
                              );
                    response = new AssistantProtocol.PlannerResponse(
                        "assistant-v2",
                        "planner.response",
                        "PLAN",
                        "Create",
                        null,
                        List.of(),
                        null,
                        List.of(
                            proposal(
                                "device",
                                "CREATE_DEVICE",
                                List.of(),
                                List.of(new AssistantProtocol.Input("name", binding)),
                                "g"
                            )
                        ),
                        List.of(
                            new AssistantProtocol.GoalProposal(
                                "g",
                                request.path("messageId").asText(),
                                "Create"
                            )
                        ),
                        List.of()
                    );
                } else response = f.prepared(
                    "CREATE_DEVICE",
                    f.mapper.createObjectNode().put("name", "Reviewed")
                );
                return new BudgetedModelCalls.Reply(
                    "JSON_TEXT",
                    f.mapper.writeValueAsString(response),
                    null
                );
            }
        };
        var availability = new ModelRouting.Availability() {
            public boolean authorized(String provider, String model) {
                return true;
            }

            public boolean qualified(
                String id,
                String model,
                String profile,
                String intent,
                String phase
            ) {
                return true;
            }

            public Set<String> outputModes(String model) {
                return Set.of("JSON_TEXT");
            }

            public AssistantProtocol.PriceSnapshot price(String id) {
                return null;
            }
        };
        var models = new RoutedIntentModels(
            new RoutedModelCalls(f.mapper, f.validator, f.registry, f.ledger(), f.traces, f.clock),
            requests,
            (input, session, phase) ->
                new RoutedIntentModels.Route(
                    f.mapper.valueToTree(policy),
                    new RoutedModelCalls.Selection(
                        phase.equals("PLANNER") ? "planner" : "economical",
                        phase,
                        null,
                        100L,
                        64L
                    ),
                    f.skills.authorized(),
                    Set.of(),
                    availability,
                    adapter,
                    response -> {
                        if (!phase.equals("PLANNER")) return;
                        try {
                            f.graph.apply(
                                input.state(),
                                f.validator.read(
                                    "plannerResponse",
                                    response,
                                    AssistantProtocol.PlannerResponse.class
                                ),
                                f.store.sourceMessages(session.owner(), f.conversation),
                                input.currentMessage().messageId().toString(),
                                true,
                                f.skills.authorized(),
                                Set.of()
                            );
                        } catch (IntentPlans.Rejected invalid) {
                            throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                                response,
                                invalid.getMessage()
                            );
                        }
                    }
                ),
            f.store,
            f.validator,
            f.runner
        );
        var runtime = f.orchestration(models);
        var state = f.send(runtime, null, "Создай устройство");
        assertEquals("WAITING_CONFIRMATION", state.status());
        assertEquals(3, network.get());
        assertEquals(
            3,
            f.jdbc.queryForObject(
                "select calls from ai_model_budget_plans where plan_id=?",
                Long.class,
                state.planId()
            )
        );
        assertEquals(
            1,
            f.jdbc.queryForObject(
                "select count(*) from ai_model_budget_attempts where plan_id=? and status='FAILED'",
                Integer.class,
                state.planId()
            )
        );
        rejectCorrection.set(true);
        var failed = f.send(runtime, null, "Подготовь ещё одно устройство");
        assertEquals("FAILED", failed.status());
        assertEquals(5, network.get(), "a repeated invalid graph stops after one correction");
        assertEquals(
            1,
            f.jdbc.queryForObject("select count(*) from ai_reviewed_plans", Integer.class)
        );
        assertTrue(f.mutations.isEmpty());
    }

    @org.junit.jupiter.api.Test
    void failedCandidateKeepsAcceptedReviewAndCannotBeConfirmedBeforeReplacement() throws Exception {
        var f = new IntentScenarioSupport();
        var stepCalls = new AtomicInteger();
        var runtime = f.orchestration(
            new com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Models() {
                public AssistantProtocol.PlannerResponse plan(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    UUID call
                ) {
                    if (
                        input.state().status().equals("FAILED")
                    ) return new AssistantProtocol.PlannerResponse(
                        "assistant-v2",
                        "planner.response",
                        "AMEND",
                        "Replace failed candidate",
                        null,
                        List.of("device"),
                        null,
                        List.of(
                            proposal(
                                "device-replacement",
                                "CREATE_DEVICE",
                                List.of(),
                                List.of(),
                                "g"
                            )
                        ),
                        List.of(),
                        List.of()
                    );
                    if (!input.state().steps().isEmpty()) return f.continued("device");
                    return f.plan(
                        input,
                        List.of(
                            proposal(
                                "device",
                                "CREATE_DEVICE",
                                List.of(),
                                List.of(
                                    new AssistantProtocol.Input(
                                        "name",
                                        new AssistantProtocol.BindingLiteral(
                                            "literal",
                                            f.mapper.valueToTree("Planner's old name")
                                        )
                                    )
                                ),
                                "g"
                            )
                        )
                    );
                }

                public AssistantProtocol.StepResponse step(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    UUID call
                ) {
                    int number = stepCalls.incrementAndGet();
                    if (number > 1) {
                        var previous = input.state().focus().reviewId();
                        org.junit.jupiter.api.Assertions.assertNotNull(previous);
                        assertTrue(
                            List.of("READY", "SUPERSEDED").contains(
                                f.reviews
                                    .find(UUID.fromString(previous), f.session.owner())
                                    .status()
                            )
                        );
                        assertTrue(
                            input
                                .state()
                                .steps()
                                .stream()
                                .anyMatch(step -> step.status().equals("PREPARING"))
                        );
                        assertTrue(
                            !input.resolvedInputs().containsKey("name"),
                            "a stale planner literal must not override the accepted review or current edit"
                        );
                        assertEquals(
                            1,
                            input.state().step("device").inputs().size(),
                            "the original proposal remains in persisted history"
                        );
                    }
                    if (number == 2) throw new IllegalArgumentException(
                        "FIXTURE_REJECTED_CANDIDATE"
                    );
                    return f.prepared(
                        "CREATE_DEVICE",
                        f.mapper.createObjectNode().put("name", input.currentMessage().text())
                    );
                }
            }
        );
        var accepted = f.send(runtime, null, "Первое имя");
        var original = accepted.reviews().get("device");
        var failed = f.send(runtime, accepted, "Изменённое имя");
        assertEquals("FAILED", failed.status());
        assertEquals(original, failed.reviews().get("device"));
        assertEquals("READY", f.reviews.find(original.reviewId(), f.session.owner()).status());
        var journal = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal.class
        );
        org.mockito.Mockito.when(journal.current(f.session.owner(), f.conversation)).thenReturn(
            java.util.Optional.of(failed)
        );
        var oldResponse = f.mapper.treeToValue(
            f.mapper
                .createObjectNode()
                .put("planId", original.reviewId().toString())
                .put("planningStatus", "READY"),
            com.voltcanary.aiagent.dto.PlanResponse.class
        );
        var reviewApi = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.service.AiAgentOrchestrator.class
        );
        org.mockito.Mockito.when(
            reviewApi.getPlan(f.session.user(), original.reviewId(), f.session.authorization())
        ).thenReturn(oldResponse);
        var conversations = new com.voltcanary.aiagent.conversation.ConversationStore(
            f.jdbc,
            f.transactions,
            new com.voltcanary.aiagent.config.ConversationProperties(),
            f.mapper
        );
        var primary = new com.voltcanary.aiagent.service.PrimaryAssistant(
            new com.voltcanary.aiagent.service.PrimaryAssistant.Ports(
                null,
                journal,
                f.store,
                conversations,
                reviewApi,
                null,
                null,
                f.contexts,
                f.mapper,
                null,
                null
            )
        );
        var detail = primary.detail(f.session.user(), f.conversation, f.session.authorization());
        assertEquals("FAILED", detail.pendingStatus());
        assertEquals(original.reviewId(), detail.acceptedReview().planId());
        assertEquals("FAILED", detail.plan().planningStatus());
        f.jdbc.update(
            "update ai_conversation_requests set status='PENDING' where conversation_id=?",
            f.conversation
        );
        var pending = primary.detail(f.session.user(), f.conversation, f.session.authorization());
        assertEquals("PENDING", pending.pendingStatus());
        org.junit.jupiter.api.Assertions.assertNull(pending.plan());
        assertEquals(original.reviewId(), pending.acceptedReview().planId());
        f.jdbc.update(
            "update ai_conversation_requests set status='COMPLETED' where conversation_id=?",
            f.conversation
        );
        assertThrows(IntentPlans.Rejected.class, () -> f.confirm(failed, "device"));
        f.restart();
        var refreshed = f.store.read(f.session.owner(), failed.planId());
        var replacement = f.send(runtime, refreshed, "Точное новое имя");
        assertEquals("WAITING_CONFIRMATION", replacement.status());
        assertNotEquals(
            original.reviewId(),
            replacement.reviews().get("device-replacement").reviewId()
        );
        assertEquals("SUPERSEDED", f.reviews.find(original.reviewId(), f.session.owner()).status());
        assertEquals(
            "Точное новое имя",
            f.reviews
                .find(replacement.reviews().get("device-replacement").reviewId(), f.session.owner())
                .plan()
                .actions()
                .getFirst()
                .parameters()
                .get("name")
        );
        var controls = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.service.PrimaryRuntime.class
        );
        org.mockito.Mockito.when(controls.actions()).thenReturn(f.actions());
        var withoutModels = new com.voltcanary.aiagent.service.PrimaryAssistant(
            new com.voltcanary.aiagent.service.PrimaryAssistant.Ports(
                controls,
                journal,
                f.store,
                conversations,
                reviewApi,
                null,
                null,
                f.contexts,
                f.mapper,
                null,
                null
            )
        );
        var cancelled = withoutModels.control(
            f.session.user(),
            replacement.planId(),
            new AssistantProtocol.ControlRequest(
                "assistant-v2",
                "control.request",
                UUID.randomUUID().toString(),
                replacement.revision(),
                "CANCEL",
                null
            ),
            f.session.authorization()
        );
        assertEquals(
            "CANCELLED",
            cancelled.status(),
            "cancellation must not resolve or call an unavailable model"
        );
        assertTrue(f.mutations.isEmpty());
    }

    @Test
    void sharedBudgetAmendmentAndCancellationRejectStaleModelResults() throws Exception {
        var f = new IntentScenarioSupport();
        var ledger = f.ledger();
        var calls = new AtomicInteger();
        var planner = new AtomicInteger();
        var steps = new AtomicInteger();
        var limits = new AssistantProtocol.Limits(
            6L,
            4_000_000L,
            10_000L,
            60_000L,
            100_000L,
            "USD"
        );
        var candidate = new AssistantProtocol.Candidate(
            "fixture-provider",
            "fixture-model",
            "offline-qualified",
            1_000_000L,
            900_000L,
            1000L,
            "JSON_TEXT",
            null
        );
        var policy = new AssistantProtocol.RoutingPolicy(
            "assistant-v2",
            "routing.policy",
            "policy",
            "1",
            "AUTO",
            null,
            List.of(
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "planner",
                    List.of(),
                    List.of("PLANNER"),
                    List.of(candidate),
                    false
                ),
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "economical",
                    List.of("CREATE_DEVICE", "CONNECT_SOURCE", "CREATE_DASHBOARD"),
                    List.of("STEP", "REPAIR"),
                    List.of(candidate),
                    false
                )
            ),
            limits,
            limits,
            "RESERVED_UPPER_BOUND",
            500L,
            BigDecimal.ONE,
            null
        );
        var availability = new ModelRouting.Availability() {
            public boolean authorized(String provider, String model) {
                return true;
            }

            public boolean qualified(
                String id,
                String model,
                String profile,
                String intent,
                String phase
            ) {
                return true;
            }

            public Set<String> outputModes(String model) {
                return Set.of("JSON_TEXT");
            }

            public AssistantProtocol.PriceSnapshot price(String id) {
                return null;
            }
        };
        var history = new com.voltcanary.aiagent.adapter.JdbcContextHistory(f.jdbc, f.clock);
        var loading = new com.voltcanary.aiagent.orchestration.context.ContextLoading(
            f.registry,
            f.validator,
            new com.voltcanary.aiagent.adapter.JdbcContextCursors(f.jdbc),
            List.of(),
            f.clock
        );
        var requests = new com.voltcanary.aiagent.orchestration.context.BoundedSkillRequests(
            f.mapper,
            f.validator,
            f.registry,
            f.store,
            history,
            loading,
            (input, session) -> f.skills,
            f.receipts,
            new com.voltcanary.aiagent.adapter.ScopedReviewContext(
                f.reviews,
                f.store,
                f.contexts,
                f.validator,
                f.mapper
            ),
            f.clock,
            100_000
        );
        var adapter = new RoutedModelCalls.WireAdapter() {
            public String serialize(JsonNode request, JsonNode schema, String mode) {
                return f.mapper
                    .createObjectNode()
                    .setAll(Map.of("request", request, "responseSchema", schema))
                    .toString();
            }

            public BudgetedModelCalls.Reply send(
                String body,
                ModelRouting.Route route,
                long timeout
            ) throws Exception {
                calls.incrementAndGet();
                JsonNode request = f.mapper.readTree(body).path("request");
                Object response;
                if (request.path("kind").asText().equals("planner.request")) {
                    int number = planner.incrementAndGet();
                    if (number == 1) response = new AssistantProtocol.PlannerResponse(
                        "assistant-v2",
                        "planner.response",
                        "PLAN",
                        "Два устройства",
                        null,
                        List.of(),
                        null,
                        List.of(
                            proposal("a", "CREATE_DEVICE", List.of(), List.of(), "g1"),
                            proposal("b", "CREATE_DEVICE", List.of(), List.of(), "g2")
                        ),
                        List.of(goal("g1", request), goal("g2", request)),
                        List.of()
                    );
                    else if (number == 2) response = new AssistantProtocol.PlannerResponse(
                        "assistant-v2",
                        "planner.response",
                        "AMEND",
                        "Явная замена незавершённых шагов",
                        null,
                        List.of("a", "b"),
                        null,
                        List.of(proposal("c", "CREATE_DEVICE", List.of(), List.of(), "g3")),
                        List.of(goal("g3", request)),
                        List.of("g1", "g2")
                    );
                    else response = f.continued("c");
                } else {
                    int number = steps.incrementAndGet();
                    {
                        if (number == 3) {
                            UUID plan = UUID.fromString(request.path("planId").asText());
                            var active = f.store.read(f.session.owner(), plan);
                            f.actions().control(
                                f.session,
                                plan,
                                new AssistantProtocol.ControlRequest(
                                    "assistant-v2",
                                    "control.request",
                                    UUID.randomUUID().toString(),
                                    active.revision(),
                                    "CANCEL",
                                    null
                                )
                            );
                        }
                        response = f.prepared(
                            "CREATE_DEVICE",
                            f.mapper.createObjectNode().put("name", "Автобус")
                        );
                    }
                }
                return new BudgetedModelCalls.Reply(
                    "JSON_TEXT",
                    f.mapper.writeValueAsString(response),
                    null
                );
            }
        };
        var a2 = new RoutedModelCalls(f.mapper, f.validator, f.registry, ledger, f.traces, f.clock);
        var models = new RoutedIntentModels(
            a2,
            requests,
            (input, session, phase) ->
                new RoutedIntentModels.Route(
                    f.mapper.valueToTree(policy),
                    new RoutedModelCalls.Selection(
                        phase.equals("PLANNER") ? "planner" : "economical",
                        phase,
                        null,
                        100L,
                        64L
                    ),
                    f.skills.authorized(),
                    Set.of(),
                    availability,
                    adapter
                ),
            f.store,
            f.validator,
            f.runner
        );
        var runtime = f.orchestration(models);
        var original = f.submit("Create two devices, keep their settings separate", 0, null);
        var state = runtime.submit(
            f.session,
            f.conversation,
            null,
            original,
            "policy",
            "1",
            f.skills
        );
        assertEquals(
            "WAITING_CONFIRMATION",
            state.status(),
            f.mapper.valueToTree(state).toString() + f.traceEvents.toString()
        );
        assertEquals(2, state.steps().size());
        var oldReview = state.reviews().get("a");
        UUID plan = state.planId();
        assertEquals(2, calls.get());
        var changed = f.submit("Вместо двух создай один трамвай", state.revision(), null);
        state = runtime.submit(f.session, f.conversation, plan, changed, "policy", "1", f.skills);
        assertEquals("WAITING_CONFIRMATION", state.status());
        assertEquals("SUPERSEDED", state.step("a").status());
        assertEquals("SUPERSEDED", state.step("b").status());
        assertEquals("WAITING_CONFIRMATION", state.step("c").status());
        assertEquals(
            "SUPERSEDED",
            f.reviews.find(oldReview.reviewId(), f.session.owner()).status()
        );
        assertEquals(4, calls.get(), "a new edit uses planner and selected skill exactly once");
        assertEquals(
            2,
            f.jdbc.queryForObject(
                "select calls from ai_model_budget_turns where plan_id=? and turn_id=?",
                Long.class,
                plan,
                UUID.fromString(changed.requestId())
            )
        );
        assertEquals(
            4,
            f.jdbc.queryForObject(
                "select calls from ai_model_budget_plans where plan_id=?",
                Long.class,
                plan
            )
        );
        f.restart();
        int count = calls.get();
        runtime.submit(f.session, f.conversation, plan, changed, "policy", "1", f.skills);
        assertEquals(count, calls.get(), "duplicate delivery must not call a model again");
        var changedPayload = new AssistantProtocol.TurnSubmit(
            changed.version(),
            changed.kind(),
            changed.requestId(),
            changed.expectedRevision(),
            "different payload",
            null,
            List.of(),
            changed.routing(),
            null
        );
        assertThrows(IntentPlans.Rejected.class, () ->
            runtime.submit(f.session, f.conversation, plan, changedPayload, "policy", "1", f.skills)
        );
        var beforeLate = f.store.read(f.session.owner(), plan);
        assertThrows(IntentPlans.Rejected.class, () ->
            f.send(runtime, beforeLate, "Продолжай с трамваем")
        );
        var cancelled = f.store.read(f.session.owner(), plan);
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", cancelled.step("c").status());
        assertTrue(f.mutations.isEmpty());
        assertEquals(
            2,
            f.jdbc.queryForObject("select count(*) from ai_reviewed_plans", Integer.class),
            "late response must not publish a new review"
        );
        assertEquals(6, calls.get());
        assertEquals(
            true,
            f.jdbc.queryForObject(
                "select cancelled from ai_model_budget_plans where plan_id=?",
                Boolean.class,
                plan
            )
        );
        assertTrue(f.store.resources(f.session.owner(), f.conversation, 0, 100).isEmpty());

        // Typed graph checks include forward declarations, cycles, output types and unavailable intents.
        var seed = new IntentPlans.State(
            UUID.randomUUID(),
            f.conversation,
            0,
            "ACTIVE",
            List.of(),
            List.of(),
            List.of(),
            null,
            new AssistantProtocol.Focus(null, null, null, null, "NONE"),
            Map.of(),
            Map.of(),
            Map.of(),
            "policy",
            "1",
            f.now.plusSeconds(1800),
            List.of()
        );
        String source = f.store.sourceMessages(f.session.owner(), f.conversation).iterator().next();
        var goals = List.of(
            new AssistantProtocol.GoalProposal("x", source, "Connect"),
            new AssistantProtocol.GoalProposal("y", source, "Create")
        );
        var forward = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "PLAN",
            "Forward dependency",
            null,
            List.of(),
            null,
            List.of(
                proposal(
                    "connect",
                    "CONNECT_SOURCE",
                    List.of("create"),
                    List.of(
                        new AssistantProtocol.Input(
                            "deviceId",
                            new AssistantProtocol.BindingStepOutput(
                                "step_output",
                                "create",
                                "deviceId"
                            )
                        )
                    ),
                    "x"
                ),
                proposal("create", "CREATE_DEVICE", List.of(), List.of(), "y")
            ),
            goals,
            List.of()
        );
        var checked = f.graph.apply(
            seed,
            forward,
            Set.of(source),
            source,
            true,
            f.skills.authorized(),
            Set.of()
        );
        assertEquals("BLOCKED", checked.step("connect").status());
        assertEquals("READY", checked.step("create").status());
        var missingDependency = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "PLAN",
            "Missing dependency",
            null,
            List.of(),
            null,
            List.of(
                proposal(
                    "connect",
                    "CONNECT_SOURCE",
                    List.of(),
                    forward.steps().getFirst().inputs(),
                    "x"
                ),
                forward.steps().get(1)
            ),
            goals,
            List.of()
        );
        assertEquals(
            "OUTPUT_WITHOUT_DEPENDENCY",
            assertThrows(IntentPlans.Rejected.class, () ->
                f.graph.apply(
                    seed,
                    missingDependency,
                    Set.of(source),
                    source,
                    true,
                    f.skills.authorized(),
                    Set.of()
                )
            ).getMessage()
        );
        String newerMessage = UUID.randomUUID().toString();
        var staleSource = assertThrows(IntentPlans.Rejected.class, () ->
            f.graph.apply(
                seed,
                forward,
                Set.of(source, newerMessage),
                newerMessage,
                true,
                f.skills.authorized(),
                Set.of()
            )
        );
        assertEquals("GOAL_PROVENANCE_OR_ID", staleSource.getMessage());
        var cycle = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "PLAN",
            "cycle",
            null,
            List.of(),
            null,
            List.of(
                proposal("a", "CREATE_DEVICE", List.of("b"), List.of(), "x"),
                proposal("b", "CREATE_DEVICE", List.of("a"), List.of(), "y")
            ),
            goals,
            List.of()
        );
        assertThrows(IntentPlans.Rejected.class, () ->
            f.graph.apply(
                seed,
                cycle,
                Set.of(source),
                source,
                true,
                f.skills.authorized(),
                Set.of()
            )
        );
        var wrongType = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "PLAN",
            "type mismatch",
            null,
            List.of(),
            null,
            List.of(
                proposal("source", "CONNECT_SOURCE", List.of(), List.of(), "x"),
                proposal(
                    "use",
                    "CONNECT_SOURCE",
                    List.of("source"),
                    List.of(
                        new AssistantProtocol.Input(
                            "deviceId",
                            new AssistantProtocol.BindingStepOutput(
                                "step_output",
                                "source",
                                "sourceId"
                            )
                        )
                    ),
                    "y"
                )
            ),
            goals,
            List.of()
        );
        assertThrows(IntentPlans.Rejected.class, () ->
            f.graph.apply(
                seed,
                wrongType,
                Set.of(source),
                source,
                true,
                f.skills.authorized(),
                Set.of()
            )
        );
        var unknown = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "PLAN",
            "unknown",
            null,
            List.of(),
            null,
            List.of(proposal("a", "UNAVAILABLE", List.of(), List.of(), "x")),
            goals.subList(0, 1),
            List.of()
        );
        assertThrows(IllegalArgumentException.class, () ->
            f.graph.apply(
                seed,
                unknown,
                Set.of(source),
                source,
                true,
                f.skills.authorized(),
                Set.of()
            )
        );
        var unconfirmed = new IntentPlans.Confirmed(
            new AssistantProtocol.Fact(
                "proposed",
                "deviceId",
                f.mapper.valueToTree(UUID.randomUUID().toString()),
                "MODEL_PROPOSED",
                source,
                null,
                "PROPOSED"
            ),
            "device-id",
            null
        );
        var proposedStep = new AssistantProtocol.StepState(
            "use",
            "CONNECT_SOURCE",
            "1",
            "assistant-v2",
            0L,
            List.of(),
            List.of(
                new AssistantProtocol.Input(
                    "deviceId",
                    new AssistantProtocol.BindingFactRef("fact_ref", "proposed")
                )
            ),
            List.of("x"),
            "READY",
            null,
            null,
            null
        );
        var proposedState = seed.revise(
            0,
            "ACTIVE",
            List.of(proposedStep),
            List.of(),
            List.of(unconfirmed),
            null,
            seed.focus(),
            Map.of(),
            Map.of(),
            Map.of()
        );
        assertThrows(IntentPlans.Rejected.class, () ->
            new ConfirmedInputs(f.receipts).resolve(proposedState, "use", f.session)
        );
        assertNotEquals("SUCCEEDED", cancelled.step("c").status());
    }

    private static AssistantProtocol.StepProposal proposal(
        String id,
        String intent,
        List<String> dependencies,
        List<AssistantProtocol.Input> inputs,
        String goal
    ) {
        return new AssistantProtocol.StepProposal(id, intent, dependencies, inputs, List.of(goal));
    }

    private static AssistantProtocol.GoalProposal goal(String id, JsonNode request) {
        return new AssistantProtocol.GoalProposal(
            id,
            request.path("messageId").asText(),
            "Retained requirement " + id
        );
    }
}
