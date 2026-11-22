package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.llm.LlmActionDraft;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.llm.LlmPlanSanitizer;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.llm.adapter.AiModelAdapter;
import com.voltcanary.aiagent.llm.adapter.AiModelAdapterRegistry;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.PlanningQuestion;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskMemoryPlanningTest {

    @Test
    void repairsDeviceContractOnceWithSameModelAndPreservedFacts() throws Exception {
        for (boolean repaired : List.of(true, false)) {
            var user = new AuthenticatedUser(UUID.randomUUID(), "device@example.test", "USER");
            var provider = new AiModelProvider();
            provider.setId(UUID.randomUUID());
            provider.setName("Test");
            provider.setProviderType(AiModelProviderType.OPENAI);
            var model = new AiModelConfig();
            model.setId(UUID.randomUUID());
            model.setModelId("test-model");
            var resolver = mock(AiModelResolver.class);
            when(resolver.resolve(user, null, null)).thenReturn(
                Optional.of(new AiResolvedModel(provider, model, "test-only"))
            );
            var adapter = mock(AiModelAdapter.class);
            var registry = mock(AiModelAdapterRegistry.class);
            when(registry.adapterFor(AiModelProviderType.OPENAI)).thenReturn(adapter);
            var invalid = new LlmPlanDraft(
                "test",
                "test-model",
                "Review",
                "Review",
                List.of(
                    new LlmActionDraft(
                        "CREATE_DEVICE",
                        "Device",
                        "Device",
                        Map.of("name", "bus22", "typeRef", UUID.randomUUID().toString())
                    )
                )
            );
            var valid = new LlmPlanDraft(
                "test",
                "test-model",
                "Review",
                "Review",
                List.of(
                    new LlmActionDraft(
                        "CREATE_DEVICE",
                        "Device",
                        "Device",
                        Map.of("name", "bus22", "type", "VEHICLE")
                    )
                )
            );
            when(adapter.plan(any())).thenReturn(invalid, repaired ? valid : invalid);
            var planner = new ActionPlanner(
                List.of(),
                new LlmPlanSanitizer(),
                resolver,
                registry,
                mock(AiAgentModelRunRecorder.class)
            );
            var result = planner.plan(
                user,
                "Создай автобус bus22",
                Map.of(
                    "originalDeviceRequest",
                    "Создай автобус bus22",
                    "name",
                    "bus22",
                    "type",
                    "VEHICLE"
                ),
                null,
                null
            );
            var requests = org.mockito.ArgumentCaptor.forClass(
                com.voltcanary.aiagent.llm.adapter.AiModelPlanRequest.class
            );
            verify(adapter, times(2)).plan(requests.capture());
            assertThat(requests.getAllValues().get(1).context())
                .containsEntry("name", "bus22")
                .containsEntry("type", "VEHICLE")
                .containsKey("deviceContractRepair");
            assertThat(requests.getAllValues().getFirst().systemPrompt())
                .contains("device-creation-v1", "OPTIONAL")
                .doesNotContain("CREATE_DASHBOARD");
            assertThat(requests.getAllValues().get(1).resolvedModel()).isEqualTo(
                requests.getAllValues().getFirst().resolvedModel()
            );
            assertThat(result.actions()).hasSize(repaired ? 1 : 0);
            if (!repaired) assertThat(result.summary()).isEqualTo(
                "Не удалось подготовить изменения"
            );
        }
    }

    @Test
    void repairsQuestionAboutKnownValuesBeforeReturningToUser() throws Exception {
        var user = new AuthenticatedUser(UUID.randomUUID(), "memory@example.test", "USER");
        var provider = new AiModelProvider();
        provider.setId(UUID.randomUUID());
        provider.setName("Test");
        provider.setProviderType(AiModelProviderType.OPENAI);
        var model = new AiModelConfig();
        model.setId(UUID.randomUUID());
        model.setModelId("test-model");
        var resolver = mock(AiModelResolver.class);
        when(resolver.resolve(user, null, null)).thenReturn(
            Optional.of(new AiResolvedModel(provider, model, "test-only"))
        );
        var adapter = mock(AiModelAdapter.class);
        var registry = mock(AiModelAdapterRegistry.class);
        when(registry.adapterFor(AiModelProviderType.OPENAI)).thenReturn(adapter);
        when(adapter.plan(any())).thenReturn(
            new LlmPlanDraft(
                "test",
                "test-model",
                "Question",
                "Rename?",
                List.of(),
                new PlanningQuestion(
                    List.of("name"),
                    "Use another name",
                    "Another name",
                    Map.of("name", "Another name")
                )
            ),
            new LlmPlanDraft(
                "test",
                "test-model",
                "Review",
                "Width updated",
                List.of(
                    new LlmActionDraft(
                        "CREATE_DASHBOARD",
                        "Review",
                        "Review",
                        Map.of("name", "Мой трамвай", "widgets", List.of())
                    )
                )
            )
        );
        var planner = new ActionPlanner(
            List.of(),
            new LlmPlanSanitizer(),
            resolver,
            registry,
            mock(AiAgentModelRunRecorder.class)
        );
        var result = planner.plan(
            user,
            "Измени ширину дашборда",
            Map.of("name", "Мой трамвай"),
            null,
            null
        );
        assertThat(result.actions()).hasSize(1);
        assertThat(result.clarification()).isNull();
        verify(adapter, times(2)).plan(any());
    }

    @Test
    void repairsUnsupportedWidgetParametersWithSameModelBeforeShowingReview() throws Exception {
        var user = new AuthenticatedUser(UUID.randomUUID(), "widgets@example.test", "USER");
        var provider = new AiModelProvider();
        provider.setId(UUID.randomUUID());
        provider.setName("Test");
        provider.setProviderType(AiModelProviderType.OPENAI);
        var model = new AiModelConfig();
        model.setId(UUID.randomUUID());
        model.setModelId("test-model");
        var resolver = mock(AiModelResolver.class);
        when(resolver.resolve(user, null, null)).thenReturn(
            Optional.of(new AiResolvedModel(provider, model, "test-only"))
        );
        var adapter = mock(AiModelAdapter.class);
        var registry = mock(AiModelAdapterRegistry.class);
        when(registry.adapterFor(AiModelProviderType.OPENAI)).thenReturn(adapter);
        var invalid = new LlmPlanDraft(
            "test",
            "test-model",
            "Review",
            "Review",
            List.of(
                new LlmActionDraft(
                    "CREATE_DASHBOARD",
                    "Map",
                    "Map",
                    Map.of(
                        "name",
                        "Bus",
                        "widgets",
                        List.of(
                            Map.of(
                                "key",
                                "map",
                                "type",
                                "vehicle-map",
                                "title",
                                "Map",
                                "presentation",
                                Map.of("showTitle", false)
                            )
                        )
                    )
                )
            )
        );
        var valid = new LlmPlanDraft(
            "test",
            "test-model",
            "Review",
            "Review",
            List.of(
                new LlmActionDraft(
                    "CREATE_DASHBOARD",
                    "Map",
                    "Map",
                    Map.of(
                        "name",
                        "Bus",
                        "widgets",
                        List.of(
                            Map.of(
                                "key",
                                "map",
                                "type",
                                "vehicle-map",
                                "title",
                                "Map",
                                "showTitle",
                                false
                            )
                        )
                    )
                )
            )
        );
        when(adapter.plan(any())).thenReturn(invalid, valid);
        var planner = new ActionPlanner(
            List.of(),
            new LlmPlanSanitizer(),
            resolver,
            registry,
            mock(AiAgentModelRunRecorder.class)
        );
        var result = planner.plan(user, "Create dashboard", Map.of(), null, null);
        assertThat(result.actions()).hasSize(1);
        var requests = org.mockito.ArgumentCaptor.forClass(
            com.voltcanary.aiagent.llm.adapter.AiModelPlanRequest.class
        );
        verify(adapter, times(2)).plan(requests.capture());
        assertThat(requests.getAllValues().get(1).context()).containsKey("compositionRepair");
        assertThat(result.explanation()).doesNotContain("supported parameters");
    }

    @Test
    void refinementUsesLatestAnswerAndKeepsEditsThroughTheRealSanitizer() throws Exception {
        var user = new AuthenticatedUser(UUID.randomUUID(), "widgets@example.test", "USER");
        var provider = new AiModelProvider();
        provider.setId(UUID.randomUUID());
        provider.setName("Test");
        provider.setProviderType(AiModelProviderType.OPENAI);
        var model = new AiModelConfig();
        model.setId(UUID.randomUUID());
        model.setModelId("test-model");
        var resolver = mock(AiModelResolver.class);
        when(resolver.resolve(user, null, null)).thenReturn(
            Optional.of(new AiResolvedModel(provider, model, "test-only"))
        );
        var adapter = mock(AiModelAdapter.class);
        var registry = mock(AiModelAdapterRegistry.class);
        when(registry.adapterFor(AiModelProviderType.OPENAI)).thenReturn(adapter);
        var edits = Map.<String, Object>of(
            "widgetEdits",
            List.of(Map.of("key", "map", "height", 8))
        );
        when(adapter.plan(any())).thenReturn(
            new LlmPlanDraft(
                "test",
                "test-model",
                "Review",
                "Resize only",
                List.of(new LlmActionDraft("CREATE_DASHBOARD", "Edit", "Edit", edits))
            )
        );
        var planner = new ActionPlanner(
            List.of(),
            new LlmPlanSanitizer(),
            resolver,
            registry,
            mock(AiAgentModelRunRecorder.class)
        );
        var result = planner.plan(
            user,
            "Original request and older answers",
            Map.of(
                "previousDashboardIntent",
                Map.of("widgets", List.of(Map.of("key", "map"))),
                "clarificationAnswers",
                List.of("Older answer", "Make map taller")
            ),
            null,
            null
        );
        assertThat(result.actions().getFirst().parameters()).isEqualTo(edits);
        var requests = org.mockito.ArgumentCaptor.forClass(
            com.voltcanary.aiagent.llm.adapter.AiModelPlanRequest.class
        );
        verify(adapter).plan(requests.capture());
        assertThat(requests.getValue().message()).isEqualTo("Make map taller");
        assertThat(requests.getValue().systemPrompt())
            .contains("EXISTING reviewed")
            .doesNotContain("INITIAL composition");
    }

    @Test
    void emptyCreationParametersAreRejectedAsCompositionRatherThanFallingBackToLegacyTemplates() {
        var draft = new LlmPlanDraft(
            "test",
            "test",
            "Review",
            "Review",
            List.of(
                new LlmActionDraft(
                    "CREATE_DASHBOARD",
                    "Review",
                    "Review",
                    Map.of("name", "Мой трамвай")
                )
            )
        );
        assertThat(
            DashboardCompositionRepair.required(draft, Map.of("dashboardWidgetTypes", List.of()))
        ).isTrue();
        assertThat(DashboardCompositionRepair.required(draft, Map.of())).isFalse();
    }

    @Test
    void anEmptyEditCannotAcknowledgeANewUserChangeEvenWithoutAnEarlierFailure() {
        var draft = new LlmPlanDraft(
            "test",
            "test",
            "Review",
            "Unchanged",
            List.of(
                new LlmActionDraft(
                    "CREATE_DASHBOARD",
                    "Review",
                    "Review",
                    Map.of("widgetEdits", List.of())
                )
            )
        );
        assertThat(
            DashboardCompositionRepair.required(
                draft,
                Map.of("previousDashboardIntent", Map.of("widgets", List.of(Map.of("key", "map"))))
            )
        ).isTrue();
    }
}
