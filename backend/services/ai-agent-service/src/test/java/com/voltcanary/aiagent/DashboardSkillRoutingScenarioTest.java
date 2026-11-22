package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanGraph;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DashboardSkillRoutingScenarioTest {

    @Test
    void openDesignThenExactRenameThenMoveUseNewSkillsAndPreserveReviewOnFailure() throws Exception {
        var f = new IntentScenarioSupport();
        var registry = new SkillRegistry(
            f.validator,
            PrimarySkillPackages.build(f.mapper, f.validator, List.of())
        );
        var skills = new IntentOrchestration.Skills(
            Set.of("CREATE_DASHBOARD", "REFINE_DASHBOARD_REVIEW"),
            Set.of()
        );
        var plans = new AtomicInteger();
        var steps = new AtomicInteger();
        var models = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID call
            ) {
                int n = plans.incrementAndGet();
                assertTrue(
                    input
                        .currentMessage()
                        .text()
                        .contains(n == 1 ? "усмотрение" : n == 2 ? "назови" : "вверх")
                );
                if (n > 1) assertNotNull(input.state().focus().reviewId());
                var goal = new AssistantProtocol.GoalProposal(
                    "g" + n,
                    input.currentMessage().messageId().toString(),
                    input.currentMessage().text()
                );
                var goals = java.util.stream.IntStream.rangeClosed(1, n)
                    .mapToObj(i -> "g" + i)
                    .toList();
                return new AssistantProtocol.PlannerResponse(
                    "assistant-v2",
                    "planner.response",
                    n == 1 ? "PLAN" : "AMEND",
                    "Route only",
                    null,
                    n == 1 ? List.of() : List.of("d" + (n - 1)),
                    null,
                    List.of(
                        new AssistantProtocol.StepProposal(
                            "d" + n,
                            n == 1 ? "CREATE_DASHBOARD" : "REFINE_DASHBOARD_REVIEW",
                            List.of(),
                            List.of(),
                            goals
                        )
                    ),
                    List.of(goal),
                    List.of()
                );
            }

            public AssistantProtocol.StepResponse step(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID call
            ) {
                int n = steps.incrementAndGet();
                var step = input
                    .state()
                    .steps()
                    .stream()
                    .filter(s -> s.status().equals("PREPARING"))
                    .findFirst()
                    .orElseThrow();
                assertEquals(
                    n == 1 ? "CREATE_DASHBOARD" : "REFINE_DASHBOARD_REVIEW",
                    step.intentId()
                );
                assertEquals("2", step.skillVersion());
                if (n == 3) throw new IllegalArgumentException("FIXTURE_INVALID_MOVE");
                var payload = f.mapper
                    .createObjectNode()
                    .put("protocol", "dashboard-operations-v1");
                payload
                    .putArray("operations")
                    .addObject()
                    .put("op", "rename_dashboard")
                    .put("id", "name")
                    .put("name", n == 1 ? "Предложенный обзор" : "Точное имя");
                if (n == 1) payload
                    .withArray("operations")
                    .addObject()
                    .put("op", "add_note")
                    .put("id", "note")
                    .put("title", "Обзор")
                    .put("text", "Обзор");
                return new AssistantProtocol.StepResponsePrepared(
                    "assistant-v2",
                    "step.response",
                    "PREPARED",
                    "Review",
                    new AssistantProtocol.Command(step.intentId() + ":command:2", "2", payload)
                );
            }
        };
        var runtime = new IntentOrchestration(
            f.store,
            new IntentPlanGraph(registry, f.validator, f.mapper),
            models,
            f::compile,
            f.receipts,
            new IntentOrchestration.Runtime(
                f.mapper,
                f.validator,
                f.clock,
                Duration.ofMinutes(30),
                f.traces,
                new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner()
            )
        );
        var first = runtime.submit(
            f.session,
            f.conversation,
            null,
            f.submit("Дашборд на твоё усмотрение", 0, null),
            "policy",
            "1",
            skills
        );
        assertEquals("WAITING_CONFIRMATION", first.status(), () ->
            f.mapper.valueToTree(first).toString()
        );
        var second = runtime.submit(
            f.session,
            f.conversation,
            first.planId(),
            f.submit("Теперь назови Точное имя", first.revision(), null),
            "policy",
            "1",
            skills
        );
        assertEquals("WAITING_CONFIRMATION", second.status());
        assertEquals("SUPERSEDED", second.step("d1").status());
        var review = second.focus().reviewId();
        assertNotEquals(first.focus().reviewId(), review);
        var third = runtime.submit(
            f.session,
            f.conversation,
            second.planId(),
            f.submit("Перемести выбранный виджет вверх", second.revision(), null),
            "policy",
            "1",
            skills
        );
        assertEquals("FAILED", third.status());
        assertEquals(review, third.focus().reviewId());
        assertNotNull(f.reviews.find(UUID.fromString(review), f.session.owner()));
        assertEquals(3, plans.get());
        assertEquals(3, steps.get());
        assertTrue(f.mutations.isEmpty());
        assertEquals(
            2,
            f.jdbc.queryForObject("select count(*) from ai_reviewed_plans", Integer.class)
        );
    }
}
