package com.voltcanary.aiagent;

import com.voltcanary.aiagent.audit.AuditLogger;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.CreationReconciliation;
import com.voltcanary.aiagent.service.CreationTaskPlanning;
import com.voltcanary.aiagent.service.PlanClarifications;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.service.PlanningInputGuard;
import com.voltcanary.aiagent.service.ToolExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlanClarificationsTest {
    @Test void persistsReviewedWorkingIntentBeforeAnyFollowupAndRetainsItThroughQuestions() {
        var prepared = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of());
        var intent = Map.<String,Object>of("name", "Мой трамвай", "period", "LAST_24_HOURS", "deviceIds", List.of(selected),
                "widgets", List.of(Map.of("key", "map", "type", "vehicle-map")));
        var action = new com.voltcanary.aiagent.model.AgentAction(UUID.randomUUID(), com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD,
                "Review", "Review", true, Map.of("prepareIntent", intent));
        var reviewed = new AgentPlan(parent.id(), user.userId(), parent.userMessage(), "Review", "Review", List.of(), List.of(action), Instant.now());
        var memory = replies.remember(prepared, reviewed, Map.of(), 0, null, List.of());
        var state = (Map<?, ?>) memory.context().get("_clarification");
        var input = (Map<?, ?>) state.get("input");
        assertThat(input.get("name")).isEqualTo("Мой трамвай");
        assertThat(input.get("previousDashboardIntent")).isEqualTo(intent);
        var retained = replies.remember(prepared, parent, (Map<String,Object>) input, 1, parent.id(), List.of("Измени ширину"));
        var retainedState = (Map<?, ?>) retained.context().get("_clarification");
        assertThat(retainedState.get("input")).isEqualTo(input);
    }
    private final PlanStore store = mock(PlanStore.class);
    private final AuthorizedPlanningContext contexts = mock(AuthorizedPlanningContext.class);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "reply@example.test", "USER");
    private final AuthorizedPlanningContext.Scope scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
    private final PlanClarifications replies = new PlanClarifications(store, contexts, new PlanningInputGuard());
    private final AgentPlan parent = new AgentPlan(UUID.randomUUID(), user.userId(), "Создай дашборд для выбранного устройства",
            "Нужно имя", "Как назвать дашборд? Предлагаю My devices.", List.of(), List.of(), Instant.now(),
            "OPENAI", "Nano", "gpt-5-nano", UUID.randomUUID(), UUID.randomUUID(), false);
    private final String selected = UUID.randomUUID().toString();

    @BeforeEach void initialize() {
        when(contexts.scope(user, "Bearer current")).thenReturn(scope);
        when(store.find(parent.id(), scope)).thenReturn(saved("NEEDS_INPUT", Instant.now().plusSeconds(600), List.of()));
        when(store.context(parent.id(), scope)).thenReturn(Map.of("_clarification",
                Map.of("round", 0, "input", Map.of("deviceIds", List.of(selected)))));
    }

    @Test void preservesOriginalIntentSelectionAndModelWithoutChangingTheOldTask() {
        var reply = replies.prepare(user, parent.id(), "Мой автобус", "Bearer current");
        assertThat(reply.message()).contains(parent.userMessage(), "Мой автобус");
        assertThat(reply.input()).containsEntry("deviceIds", List.of(selected))
                .containsEntry("priorAssistantQuestion", parent.explanation())
                .containsEntry("clarificationAnswers", List.of("Мой автобус"));
        assertThat(reply.parent().plan().providerId()).isEqualTo(parent.providerId());
        assertThat(reply.round()).isEqualTo(1);
        verify(store, never()).claim(any(), any(), any(), any());
        verify(store, never()).save(any(), any());
    }

    @Test void layoutRefinementRecoversSelectionsFromTheCompiledParentRatherThanCandidateLists() {
        var panels = List.of(Map.of("key", "temperature-series", "width", "FULL", "height", 6));
        var intent = Map.<String, Object>of("name", "Кухня", "deviceIds", List.of(selected), "period", "LAST_7_DAYS", "panelLayout", panels);
        var action = new com.voltcanary.aiagent.model.AgentAction(UUID.randomUUID(), com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD,
                "Draft", "Review", true, Map.of("prepareIntent", intent, "templateId", "refrigeration", "templateVersion", 1));
        var compiled = new AgentPlan(parent.id(), user.userId(), parent.userMessage(), "Review", "Проверьте макет", List.of(), List.of(action), Instant.now());
        when(store.find(parent.id(), scope)).thenReturn(new PlanStore.Stored(compiled, scope.accountId(), 1, "digest",
                Instant.now().plusSeconds(600), "READY", null, List.of()));
        var answer = replies.prepare(user, parent.id(), "Убери события", "Bearer current");
        assertThat(answer.input()).containsEntry("name", "Кухня").containsEntry("deviceIds", List.of(selected));
        var previous = (Map<?, ?>) answer.input().get("previousDashboardIntent");
        assertThat(previous.get("panelLayout")).isEqualTo(panels);
        assertThat(previous.get("period")).isEqualTo("LAST_7_DAYS");
        assertThat(previous.get("templateId")).isEqualTo("refrigeration");
    }

    @Test void reconstructsOnlyExplicitEquipmentForOlderPersistedPlans() {
        var refs = List.of(new AuthorizedPlanningContext.ResourceRef("chosen", "device", selected, Map.of()),
                new AuthorizedPlanningContext.ResourceRef("candidate", "device", UUID.randomUUID().toString(), Map.of()));
        when(store.find(parent.id(), scope)).thenReturn(saved("NEEDS_INPUT", Instant.now().plusSeconds(600), refs));
        when(store.context(parent.id(), scope)).thenReturn(Map.of("selectedDashboardDeviceRefs", List.of("chosen")));
        assertThat(replies.prepare(user, parent.id(), "Первый шаблон", "Bearer current").input())
                .containsEntry("deviceIds", List.of(selected));
    }

    @Test void rejectsForeignAndUnavailableParentsBeforeReadingTheirContext() {
        when(store.find(parent.id(), scope)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> replies.prepare(user, parent.id(), "Ответ", "Bearer current"))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).context(any(), any());
    }

    @Test void rejectsExecutedRunningUnknownAndExpiredTasks() {
        for (String state : List.of("SUCCESS", "FAILED", "RUNNING", "UNKNOWN", "EXPIRED")) {
            when(store.find(parent.id(), scope)).thenReturn(saved(state, Instant.now().plusSeconds(600), List.of()));
            assertThatThrownBy(() -> replies.prepare(user, parent.id(), "Ответ", "Bearer current"))
                    .as(state).isInstanceOf(ResponseStatusException.class);
        }
        when(store.find(parent.id(), scope)).thenReturn(saved("READY", Instant.now().minusSeconds(1), List.of()));
        assertThatThrownBy(() -> replies.prepare(user, parent.id(), "Ответ", "Bearer current"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
    }

    @Test void boundsReplySizeHistoryAndCredentials() {
        for (String answer : List.of(" ", "x".repeat(2001), "Bearer fixture-credential")) {
            assertThatThrownBy(() -> replies.prepare(user, parent.id(), answer, "Bearer current"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        when(store.context(parent.id(), scope)).thenReturn(Map.of("_clarification", Map.of("round", 30, "input", Map.of())));
        assertThatThrownBy(() -> replies.prepare(user, parent.id(), "Ответ", "Bearer current"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(422));
        var longParent = new AgentPlan(parent.id(), user.userId(), "x".repeat(7990), "Need input", "Name?", List.of(), List.of(), Instant.now());
        when(store.find(parent.id(), scope)).thenReturn(new PlanStore.Stored(longParent, scope.accountId(), 1, "digest", Instant.now().plusSeconds(600), "NEEDS_INPUT", null, List.of()));
        when(store.context(parent.id(), scope)).thenReturn(Map.of());
        assertThatThrownBy(() -> replies.prepare(user, parent.id(), "Ответ", "Bearer current"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void boundedMetadataSurvivesAnotherTurnWithoutPromotingCatalogueCandidates() {
        var prepared = new AuthorizedPlanningContext.Prepared(scope, Map.of("dashboardDevices", List.of("catalogue")), List.of());
        var remembered = replies.remember(prepared, Map.of("deviceIds", List.of(selected), "accountId", "ignored"), 1,
                parent.id(), List.of("Первое имя"));
        when(store.context(parent.id(), scope)).thenReturn(remembered.context());
        var next = replies.prepare(user, parent.id(), "Первый шаблон", "Bearer current");
        assertThat(next.round()).isEqualTo(2);
        assertThat(next.answers()).containsExactly("Первое имя", "Первый шаблон");
        assertThat(next.input()).doesNotContainKeys("accountId", "dashboardDevices");
    }

    @Test void orchestrationReplansWithCurrentTokenAndPinnedModelAndNeverExecutes() {
        var planning = mock(CreationTaskPlanning.class);
        var executor = mock(ToolExecutor.class);
        var prepared = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of());
        var child = new AgentPlan(UUID.randomUUID(), user.userId(), "clarified", "Review", "Review", List.of(), List.of(), Instant.now());
        when(planning.prepare(eq(user), any(), any(), eq(parent.providerId()), eq(parent.modelConfigId()), eq("Bearer current"), any()))
                .thenReturn(new CreationTaskPlanning.PreparedTask(child, prepared));
        when(store.save(eq(child), any())).thenReturn(new PlanStore.Stored(child, scope.accountId(), 1, "new-digest",
                Instant.now().plusSeconds(600), "NEEDS_INPUT", null, List.of()));
        var service = new AiAgentOrchestrator(planning, store, executor, mock(AuditLogger.class), contexts,
                mock(CreationReconciliation.class), replies);
        assertThat(service.clarify(user, parent.id(), "Мой автобус", "Bearer current").planId()).isEqualTo(child.id());
        verifyNoInteractions(executor);
        var failed = new AgentPlan(UUID.randomUUID(), user.userId(), "clarified", "Failed", "Retry",
                List.of("Provider request failed; no alternate model was selected."), List.of(), Instant.now());
        when(planning.prepare(eq(user), any(), any(), eq(parent.providerId()), eq(parent.modelConfigId()), eq("Bearer current"), any()))
                .thenReturn(new CreationTaskPlanning.PreparedTask(failed, prepared));
        assertThatThrownBy(() -> service.clarify(user, parent.id(), "Мой автобус", "Bearer current"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
        verify(store, never()).save(eq(failed), any());
    }

    @Test void acceptsOnlyAnExactSourceReplyToAStructuredSourceQuestion() {
        String source = UUID.randomUUID().toString();
        var ref = new AuthorizedPlanningContext.ResourceRef("data", "dashboard-data", "choice", Map.of("sourceId", source, "externalSourceId", "GPS B", "sourceChoiceLabel", "Источник 2"));
        var question = new com.voltcanary.aiagent.model.PlanningQuestion(List.of("sourceId"), "GPS B", "GPS B", Map.of("sourceId", "GPS B"));
        var plan = new AgentPlan(parent.id(), user.userId(), parent.userMessage(), "Source", "Choose source", List.of(), List.of(), Instant.now(),
                "OPENAI", "test", "test", parent.providerId(), parent.modelConfigId(), false, question);
        when(store.find(parent.id(), scope)).thenReturn(new PlanStore.Stored(plan, scope.accountId(), 1, "digest", Instant.now().plusSeconds(600), "NEEDS_INPUT", null, List.of(ref)));
        assertThat(replies.prepare(user, parent.id(), "GPS B", "Bearer current").input().get("sourceId")).isEqualTo(source);
        assertThat(replies.prepare(user, parent.id(), "Источник 2", "Bearer current").input().get("sourceId")).isEqualTo(source);
        assertThat(replies.prepare(user, parent.id(), "GPS B please", "Bearer current").input()).doesNotContainKey("sourceId");
        when(store.find(parent.id(), scope)).thenReturn(saved("NEEDS_INPUT", Instant.now().plusSeconds(600), List.of(ref)));
        assertThat(replies.prepare(user, parent.id(), "GPS B", "Bearer current").input()).doesNotContainKey("sourceId");
        when(store.context(parent.id(), scope)).thenReturn(Map.of("name", "Known", "dashboardDataDiagnostics", List.of(Map.of("code", "AMBIGUOUS_SOURCE")),
                "_clarification", Map.of("round", 0, "input", Map.of("deviceIds", List.of(selected)))));
        assertThat(replies.prepare(user, parent.id(), "GPS B", "Bearer current").input().get("sourceId")).isEqualTo(source);
    }

    private PlanStore.Stored saved(String state, Instant expiry, List<AuthorizedPlanningContext.ResourceRef> refs) {
        return new PlanStore.Stored(parent, scope.accountId(), 1, "digest", expiry, state, null, refs);
    }
}
