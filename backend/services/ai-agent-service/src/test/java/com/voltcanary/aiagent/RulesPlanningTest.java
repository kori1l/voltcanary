package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voltcanary.aiagent.llm.LlmPlanSanitizer;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.llm.adapter.AiModelAdapterRegistry;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.ActionPlanner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RulesPlanningTest {

    @Test
    void persistedDraftUpdateCannotEnterTheLegacyGeneratorWithoutItsVersionedContext() {
        var draft = new com.voltcanary.aiagent.llm.LlmPlanDraft(
            "fixture",
            "fixture",
            "Update",
            "Update",
            List.of(
                new com.voltcanary.aiagent.llm.LlmActionDraft(
                    "UPDATE_DASHBOARD_DRAFT",
                    "Update",
                    "Update",
                    Map.of("dashboardId", UUID.randomUUID().toString())
                )
            )
        );
        var fallback = new com.voltcanary.aiagent.model.AgentPlan(
            UUID.randomUUID(),
            user.userId(),
            "Update",
            "Unsupported",
            "Unsupported",
            List.of(),
            List.of(),
            java.time.Instant.now()
        );
        assertThat(
            new LlmPlanSanitizer().sanitize(user, "Update", draft, fallback).actions()
        ).isEmpty();
    }

    private final ActionPlanner planner = new ActionPlanner(
        List.of(),
        new LlmPlanSanitizer(),
        mock(AiModelResolver.class),
        mock(AiModelAdapterRegistry.class),
        mock(AiAgentModelRunRecorder.class)
    );
    private final AuthenticatedUser user = new AuthenticatedUser(
        UUID.randomUUID(),
        "rules@example.test",
        "USER"
    );

    @Test
    void unsupportedRequestAndAbsentYamlDoNotInventAnExecutableValidation() {
        for (String message : List.of("Подключи реальный автобус", "Validate YAML")) {
            var plan = planner.plan(user, message, Map.of());
            assertThat(plan.actions()).isEmpty();
            assertThat(plan.explanation()).contains("AI-модель", "YAML");
        }
    }

    @Test
    void explicitValidationUsesOnlyTheSuppliedNonemptyYaml() {
        var plan = planner.plan(user, "Validate YAML", Map.of("yamlConfig", "schemaVersion: 1"));
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().getFirst().tool()).isEqualTo(AgentTool.VALIDATE_SIMULATOR_YAML);
        assertThat(plan.actions().getFirst().parameters()).containsEntry(
            "yamlConfig",
            "schemaVersion: 1"
        );
    }
}
