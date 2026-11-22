package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.PlanningQuestion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardSourceChoiceRequiredTest {

    @Test
    void structuredSourceQuestionShowsAllActualOptionsWithoutChangingItsManualPolicy() {
        var question = new PlanningQuestion(
            List.of("sourceId"),
            "Источник 1",
            "Выберите источник",
            Map.of("sourceId", "Источник 1")
        );
        var plan = new AgentPlan(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Create",
            "Choose source",
            "Укажите источник",
            List.of(),
            List.of(),
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            false,
            question
        );
        var context = new AuthorizedPlanningContext.Prepared(
            new AuthorizedPlanningContext.Scope(plan.userId(), UUID.randomUUID()),
            Map.of("name", "Bus"),
            List.of(
                new AuthorizedPlanningContext.ResourceRef(
                    "one",
                    "dashboard-data",
                    "one",
                    Map.of(
                        "requiresSourceChoice",
                        true,
                        "sourceChoiceLabel",
                        "Источник 1",
                        "externalSourceId",
                        "actual-A"
                    )
                ),
                new AuthorizedPlanningContext.ResourceRef(
                    "two",
                    "dashboard-data",
                    "two",
                    Map.of(
                        "requiresSourceChoice",
                        true,
                        "sourceChoiceLabel",
                        "Источник 2",
                        "externalSourceId",
                        "actual-B"
                    )
                )
            )
        );
        var result = DashboardSourceChoiceRequired.availableOptions(plan, context);
        assertThat(result.explanation()).contains("Источник 1 — actual-A", "Источник 2 — actual-B");
        assertThat(result.clarification()).isEqualTo(question);
        assertThat(result.clarification().requiresExplicitChoice()).isTrue();
        assertThat(result.actions()).isEmpty();
        var unrelated = new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            plan.summary(),
            plan.explanation(),
            List.of(),
            List.of(),
            plan.createdAt(),
            null,
            null,
            null,
            null,
            null,
            false,
            new PlanningQuestion(List.of("name"), "Bus", "Имя", Map.of("name", "Bus"))
        );
        assertThat(DashboardSourceChoiceRequired.availableOptions(unrelated, context)).isSameAs(
            unrelated
        );
    }
}
