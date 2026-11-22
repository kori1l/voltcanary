package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.aiagent.model.PlanningQuestion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskConversationMemoryTest {

    @Test
    void dashboardForThisBusStartsADashboardTaskRatherThanAnotherDevice() {
        for (String message : List.of(
            "Создай для этого автобуса дашборд «Обзор».",
            "Создай для него дашборд",
            "Create for this bus a dashboard",
            "Создай дашборд для автобуса"
        )) {
            assertThat(DeviceTaskContext.requested(message)).as(message).isFalse();
            assertThat(DashboardPlanningContext.recognizesDashboard(message)).as(message).isTrue();
        }
        for (String message : List.of(
            "Создай автобус «Новый» и затем дашборд",
            "Create a device and then a dashboard"
        )) {
            assertThat(DeviceTaskContext.requested(message)).as(message).isTrue();
        }
        for (String message : List.of(
            "Создай источник для устройства",
            "Create a data source for this device",
            "Создай правило для датчика",
            "Create a simulator config for a device",
            "Create a demo device",
            "Создай демо устройство",
            "Создай устройство с симулятором"
        )) {
            assertThat(DeviceTaskContext.requested(message)).as(message).isFalse();
        }
    }

    @Test
    void preservesStructuredQuestionFromProviderJsonThroughPlanSerialization() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var parser = new com.voltcanary.aiagent.llm.adapter.JsonPlanParser(mapper);
        var draft = parser.parse(
            "test",
            "test-model",
            """
            {"summary":"Уточнение","explanation":"Как назвать обзор?","actions":[],
            "clarification":{"missingFields":["name"],"suggestedReply":"Назови «Мой обзор»","suggestionLabel":"Мой обзор","suggestedValues":{"name":"Мой обзор"}}}
            """
        );
        var user = new com.voltcanary.aiagent.security.AuthenticatedUser(
            java.util.UUID.randomUUID(),
            "memory@example.test",
            "USER"
        );
        var fallback = new com.voltcanary.aiagent.model.AgentPlan(
            java.util.UUID.randomUUID(),
            user.userId(),
            "Request",
            "Fallback",
            "Fallback",
            List.of(),
            List.of(),
            java.time.Instant.now()
        );
        var plan = new com.voltcanary.aiagent.llm.LlmPlanSanitizer().sanitize(
            user,
            "Request",
            draft,
            fallback
        );
        assertThat(plan.clarification()).isEqualTo(draft.clarification());
        assertThat(plan.explanation()).isEqualTo("Как назвать обзор?");
    }

    @Test
    void identifiesKnownIntentFieldsWithoutReadingQuestionWording() {
        var context = Map.<String, Object>of(
            "previousDashboardIntent",
            Map.of(
                "name",
                "Мой трамвай",
                "period",
                "LAST_24_HOURS",
                "widgets",
                List.of("map", "speed")
            )
        );
        assertThat(
            TaskConversationMemory.asksKnown(
                new PlanningQuestion(
                    List.of("name"),
                    "Any reply",
                    "Any label",
                    Map.of("name", "Other")
                ),
                context
            )
        ).isTrue();
        assertThat(
            TaskConversationMemory.asksKnown(
                new PlanningQuestion(
                    List.of("period"),
                    "Any reply",
                    "Any label",
                    Map.of("period", "LAST_1_HOUR")
                ),
                context
            )
        ).isTrue();
        assertThat(
            TaskConversationMemory.asksKnown(
                new PlanningQuestion(
                    List.of("newField"),
                    "Any reply",
                    "Any label",
                    Map.of("newField", "Other")
                ),
                context
            )
        ).isFalse();
        assertThat(DashboardModelContext.forModel(context)).containsEntry(
            "taskMemory",
            TaskConversationMemory.facts(context)
        );
    }
}
