package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntentWorkflowScenarioTest {

    @Test
    void dependentReviewedWorkflowKeepsQuestionReceiptsAndCrossPlanIdentity() throws Exception {
        var f = new IntentScenarioSupport();
        f.jdbc.update(
            "update ai_conversations set expires_at=? where id=?",
            java.sql.Timestamp.from(f.now.plus(java.time.Duration.ofDays(90))),
            f.conversation
        );
        var plannerCalls = new AtomicInteger();
        var stepCalls = new AtomicInteger();
        OrchestrationPorts.Models fixtureModel = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID call
            ) {
                plannerCalls.incrementAndGet();
                if (!input.state().steps().isEmpty()) return f.continued(
                    input
                        .state()
                        .steps()
                        .stream()
                        .filter(step -> step.status().equals("READY"))
                        .findFirst()
                        .orElseThrow()
                        .stepId()
                );
                if (!input.state().facts().isEmpty()) {
                    var device = input
                        .state()
                        .facts()
                        .stream()
                        .filter(item -> item.type().equals("device-id"))
                        .findFirst()
                        .orElseThrow();
                    return f.plan(
                        input,
                        List.of(
                            new AssistantProtocol.StepProposal(
                                "next-dashboard",
                                "CREATE_DASHBOARD",
                                List.of(),
                                List.of(
                                    new AssistantProtocol.Input(
                                        "deviceId",
                                        new AssistantProtocol.BindingFactRef(
                                            "fact_ref",
                                            device.fact().factId()
                                        )
                                    )
                                ),
                                List.of("next-goal")
                            )
                        )
                    );
                }
                return f.plan(
                    input,
                    List.of(
                        new AssistantProtocol.StepProposal(
                            "device",
                            "CREATE_DEVICE",
                            List.of(),
                            List.of(),
                            List.of("device-goal")
                        ),
                        new AssistantProtocol.StepProposal(
                            "feed",
                            "CONNECT_SOURCE",
                            List.of("device"),
                            List.of(
                                new AssistantProtocol.Input(
                                    "deviceId",
                                    new AssistantProtocol.BindingStepOutput(
                                        "step_output",
                                        "device",
                                        "deviceId"
                                    )
                                )
                            ),
                            List.of("feed-goal")
                        ),
                        new AssistantProtocol.StepProposal(
                            "dashboard",
                            "CREATE_DASHBOARD",
                            List.of("device", "feed"),
                            List.of(
                                new AssistantProtocol.Input(
                                    "deviceId",
                                    new AssistantProtocol.BindingStepOutput(
                                        "step_output",
                                        "device",
                                        "deviceId"
                                    )
                                ),
                                new AssistantProtocol.Input(
                                    "sourceId",
                                    new AssistantProtocol.BindingStepOutput(
                                        "step_output",
                                        "feed",
                                        "sourceId"
                                    )
                                )
                            ),
                            List.of("dashboard-goal")
                        )
                    )
                );
            }

            public AssistantProtocol.StepResponse step(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID call
            ) {
                stepCalls.incrementAndGet();
                var step = input
                    .state()
                    .steps()
                    .stream()
                    .filter(item -> item.status().equals("PREPARING"))
                    .findFirst()
                    .orElseThrow();
                if (step.intentId().equals("CREATE_DEVICE")) {
                    if (
                        input.token().answeredQuestion() == null
                    ) return new AssistantProtocol.StepResponseQuestion(
                        "assistant-v2",
                        "step.response",
                        "QUESTION",
                        "Нужно имя",
                        new AssistantProtocol.Question(
                            "Как назвать устройство?",
                            List.of("name"),
                            List.of(
                                new AssistantProtocol.QuestionChoicesItem(
                                    "bus22",
                                    List.of(
                                        new AssistantProtocol.Input(
                                            "name",
                                            new AssistantProtocol.BindingLiteral(
                                                "literal",
                                                f.mapper.valueToTree("bus22")
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    );
                    assertEquals(
                        "Как назвать устройство?",
                        input.token().answeredQuestion().question().text()
                    );
                    assertEquals("bus22", input.currentMessage().text());
                    return f.prepared(
                        step.intentId(),
                        f.mapper.createObjectNode().put("name", input.currentMessage().text())
                    );
                }
                var payload = f.mapper.createObjectNode();
                input.resolvedInputs().forEach(payload::set);
                if (step.intentId().equals("CREATE_DASHBOARD")) payload.put("name", "Транспорт");
                return f.prepared(step.intentId(), payload);
            }
        };
        var runtime = f.orchestration(fixtureModel);
        var first = f.submit(
            "Создай автобус, подключи источник и собери дашборд. Сначала спроси имя.",
            0,
            null
        );
        var state = runtime.submit(f.session, f.conversation, null, first, "policy", "1", f.skills);
        assertEquals("WAITING_INPUT", state.status());
        assertEquals(1, plannerCalls.get());
        assertEquals(1, stepCalls.get());
        assertEquals(0, f.mutations.size());
        var question = state.pendingQuestion();
        assertNotNull(question.questionId());
        String originalMessage = state.goals().getFirst().sourceMessageId();
        UUID plan = state.planId();
        f.restart();
        assertEquals(question, f.store.read(f.session.owner(), plan).pendingQuestion());
        runtime = f.orchestration(fixtureModel);
        var reply = f.submit("bus22", state.revision(), state.pendingQuestion().questionId());
        state = runtime.submit(f.session, f.conversation, plan, reply, "policy", "1", f.skills);
        int callsAfterReply = plannerCalls.get() + stepCalls.get();
        runtime.submit(f.session, f.conversation, plan, reply, "policy", "1", f.skills);
        assertEquals(
            callsAfterReply,
            plannerCalls.get() + stepCalls.get(),
            "answered-question identity must not break duplicate delivery"
        );
        assertEquals(1, plannerCalls.get(), "known step reply must not add a router call");
        assertEquals("WAITING_CONFIRMATION", state.status());
        assertTrue(f.mutations.isEmpty(), "preparation must never execute domain mutations");
        var review = state.reviews().get("device");
        var wrong = new AssistantProtocol.ConfirmationRequest(
            "assistant-v2",
            "confirmation.request",
            UUID.randomUUID().toString(),
            "device",
            review.reviewId().toString(),
            state.revision(),
            (long) review.version(),
            "wrong-digest"
        );
        assertThrows(IntentPlans.Rejected.class, () -> f.actions().confirm(f.session, plan, wrong));
        assertTrue(f.mutations.isEmpty());
        state = f.store.read(f.session.owner(), plan);
        var accepted = new AssistantProtocol.ConfirmationRequest(
            "assistant-v2",
            "confirmation.request",
            UUID.randomUUID().toString(),
            "device",
            review.reviewId().toString(),
            state.revision(),
            (long) review.version(),
            review.digest()
        );
        state = f.actions().confirm(f.session, plan, accepted);
        f.actions().confirm(f.session, plan, accepted);
        assertEquals(1, f.mutations.get(AgentTool.CREATE_DEVICE).get());
        assertEquals("SUCCEEDED", state.step("device").status());
        assertEquals("READY", state.step("feed").status());
        assertEquals("BLOCKED", state.step("dashboard").status());
        String deviceId = state.facts().getFirst().fact().value().asText();
        assertTrue(
            f.jdbc.queryForObject(
                "select p.retain_until>=c.expires_at from ai_reviewed_plans p cross join ai_conversations c where p.id=? and c.id=?",
                Boolean.class,
                review.reviewId(),
                f.conversation
            )
        );
        assertEquals(originalMessage, state.facts().getFirst().fact().sourceMessageId());
        f.outcomes.put(AgentTool.CREATE_DATA_SOURCE, new ArrayList<>(List.of("FAILED", "SUCCESS")));
        state = f.send(runtime, state, "Подключи источник для него");
        state = f.confirm(state, "feed");
        assertEquals("FAILED", state.step("feed").status());
        assertEquals("SUCCEEDED", state.step("device").status());
        state = f
            .actions()
            .control(
                f.session,
                plan,
                new AssistantProtocol.ControlRequest(
                    "assistant-v2",
                    "control.request",
                    UUID.randomUUID().toString(),
                    state.revision(),
                    "RETRY",
                    "feed"
                )
            );
        assertEquals("READY", state.step("feed").status());
        assertEquals(1, f.mutations.get(AgentTool.CREATE_DEVICE).get());
        state = f.send(runtime, state, "Continue with the same device");
        state = f.confirm(state, "feed");
        assertEquals("SUCCEEDED", state.step("feed").status());
        assertEquals(2, f.mutations.get(AgentTool.CREATE_DATA_SOURCE).get());
        f.outcomes.put(AgentTool.CREATE_DASHBOARD, new ArrayList<>(List.of("UNKNOWN", "SUCCESS")));
        state = f.send(runtime, state, "Maintenant le tableau de bord");
        state = f.confirm(state, "dashboard");
        assertEquals("UNKNOWN", state.step("dashboard").status());
        assertEquals(1, f.mutations.get(AgentTool.CREATE_DASHBOARD).get());
        f.restart();
        state = f
            .actions()
            .control(
                f.session,
                plan,
                new AssistantProtocol.ControlRequest(
                    "assistant-v2",
                    "control.request",
                    UUID.randomUUID().toString(),
                    state.revision(),
                    "RETRY",
                    "dashboard"
                )
            );
        assertEquals("SUCCEEDED", state.status());
        assertEquals(
            1,
            f.mutations.get(AgentTool.CREATE_DASHBOARD).get(),
            "UNKNOWN reconciliation must not create again"
        );
        assertEquals(3, f.store.resources(f.session.owner(), f.conversation, 0, 100).size());
        int beforeRead = plannerCalls.get() + stepCalls.get();
        f.store.read(f.session.owner(), plan);
        f.store.resources(f.session.owner(), f.conversation, 0, 100);
        assertEquals(beforeRead, plannerCalls.get() + stepCalls.get());
        runtime = f.orchestration(fixtureModel);
        var newPlan = f.send(runtime, null, "Ещё один дашборд для этого автобуса");
        assertFalse(newPlan.planId().equals(plan));
        assertEquals(
            deviceId,
            newPlan
                .facts()
                .stream()
                .filter(item -> item.type().equals("device-id"))
                .findFirst()
                .orElseThrow()
                .fact()
                .value()
                .asText()
        );
        assertEquals("WAITING_CONFIRMATION", newPlan.status());
        assertEquals(1, f.mutations.get(AgentTool.CREATE_DEVICE).get());
        f.deletedResources.add(deviceId);
        int beforeDeletedSelection = plannerCalls.get() + stepCalls.get();
        var unavailable = f.send(runtime, newPlan, "Продолжи для того же устройства");
        assertEquals("FAILED", unavailable.status());
        assertEquals(
            beforeDeletedSelection,
            plannerCalls.get() + stepCalls.get(),
            "deleted selections must fail before a model call or fallback"
        );
        assertEquals(1, f.mutations.get(AgentTool.CREATE_DEVICE).get());
        var foreign = new AuthorizedPlanningContextScopeFixture();
        assertThrows(IntentPlans.Rejected.class, () -> f.store.read(foreign.scope(), plan));
        assertThrows(IntentPlans.Rejected.class, () ->
            f.store.resources(foreign.scope(), f.conversation, 0, 10)
        );
    }

    private record AuthorizedPlanningContextScopeFixture(UUID id) {
        private AuthorizedPlanningContextScopeFixture() {
            this(UUID.randomUUID());
        }

        com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope scope() {
            return new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(id, id);
        }
    }
}
