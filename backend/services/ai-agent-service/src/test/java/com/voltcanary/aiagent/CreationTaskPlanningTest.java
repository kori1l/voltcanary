package com.voltcanary.aiagent;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CreationTaskPlanningTest {
    @Test void unavailableCapabilitiesStopBeforeAnyModelCall() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER");
        var planner = mock(ActionPlanner.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var catalogue = mock(DashboardPlanningContext.class);
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID()), Map.of(), List.of());
        when(contexts.prepare(eq(user), eq("auth"), anyString(), anyMap())).thenReturn(prepared);
        when(catalogue.enrich(eq(prepared), anyMap(), eq("auth"))).thenThrow(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        var service = new CreationTaskPlanning(planner, contexts, catalogue, mock(DeviceCreationCompiler.class),
                mock(DashboardCreationCompiler.class), new PlanningInputGuard());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare(user, "Дашборд для автобуса",
                Map.of("taskKind", "DASHBOARD"), null, null, "auth"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(planner);
    }
    @Test void confirmedNameWinsOverAnOlderNameInModelOutputBeforePreparation() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER");
        var planner = mock(ActionPlanner.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var catalogue = mock(DashboardPlanningContext.class);
        var compiler = mock(DashboardCreationCompiler.class);
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID()),
                Map.of("name", "Новое имя"), List.of());
        when(contexts.prepare(eq(user), eq("auth"), anyString(), anyMap())).thenReturn(prepared);
        when(catalogue.enrich(eq(prepared), anyMap(), eq("auth"))).thenReturn(prepared);
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true, Map.of("name", "Старое имя"));
        var draft = new AgentPlan(UUID.randomUUID(), user.userId(), "Повтори изменения", "Draft", "Review", List.of(), List.of(action), Instant.now());
        when(planner.plan(eq(user), anyString(), anyMap(), isNull(), isNull())).thenReturn(draft);
        when(compiler.compile(any(), eq(prepared), eq(true), eq("auth"))).thenAnswer(call -> call.getArgument(0));
        var service = new CreationTaskPlanning(planner, contexts, catalogue, mock(DeviceCreationCompiler.class), compiler, new PlanningInputGuard());
        var result = service.prepare(user, "Повтори изменения", Map.of("taskKind", "DASHBOARD", "name", "Новое имя"), null, null, "auth");
        assertThat(result.plan().actions().getFirst().parameters()).containsEntry("name", "Новое имя");
    }
    @Test void aPersistedKnownNameCannotBecomeAnotherNameQuestion() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER");
        var planner = mock(ActionPlanner.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var catalogue = mock(DashboardPlanningContext.class);
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID()),
                Map.of("name", "Мой трамвай"), List.of());
        when(contexts.prepare(eq(user), eq("auth"), anyString(), anyMap())).thenReturn(prepared);
        when(catalogue.enrich(eq(prepared), anyMap(), eq("auth"))).thenReturn(prepared);
        var question = new com.voltcanary.aiagent.model.PlanningQuestion(List.of("name"), "Другое имя", "Имя", Map.of("name", "Другое имя"));
        var invalid = new AgentPlan(UUID.randomUUID(), user.userId(), "Сделай меньше", "Имя?", "Как назвать?", List.of(), List.of(),
                Instant.now(), "OPENAI", "test", "test", null, null, false, question);
        when(planner.plan(eq(user), anyString(), anyMap(), isNull(), isNull())).thenReturn(invalid);
        var compiler = mock(DashboardCreationCompiler.class);
        var service = new CreationTaskPlanning(planner, contexts, catalogue, mock(DeviceCreationCompiler.class), compiler, new PlanningInputGuard());
        var result = service.prepare(user, "Сделай меньше", Map.of("taskKind", "DASHBOARD", "name", "Мой трамвай"), null, null, "auth");
        assertThat(result.plan().clarification()).isNull();
        assertThat(result.plan().summary()).isEqualTo("Не удалось подготовить изменения");
        assertThat(result.context().context()).containsEntry("name", "Мой трамвай");
        verifyNoInteractions(compiler);
    }
    @Test void onlyCompiledSelectionsAreRetainedAndImplicitFrozenSourcesGetCurrentAccessChecks() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER");
        var planner = mock(ActionPlanner.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var catalogue = mock(DashboardPlanningContext.class);
        var devices = mock(DeviceCreationCompiler.class);
        var dashboards = mock(DashboardCreationCompiler.class);
        String selected = UUID.randomUUID().toString(), unrelated = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
        var scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        var prepared = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of(
                new AuthorizedPlanningContext.ResourceRef("selected", "device", selected, Map.of()),
                new AuthorizedPlanningContext.ResourceRef("unrelated", "device", unrelated, Map.of())));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true,
                Map.of("prepareIntent", Map.of("deviceIds", List.of(selected)),
                        "sourceBindings", List.of(Map.of("deviceId", selected, "sourceId", source))));
        var plan = new AgentPlan(UUID.randomUUID(), user.userId(), "dashboard", "Draft", "Review", List.of(), List.of(action), Instant.now());
        when(contexts.prepare(user, "Bearer current", "dashboard", Map.of("originalDashboardRequest", "dashboard"))).thenReturn(prepared);
        when(catalogue.requested("dashboard")).thenReturn(true);
        when(catalogue.enrich(prepared, Map.of("originalDashboardRequest", "dashboard"), "Bearer current")).thenReturn(prepared);
        when(planner.plan(user, "dashboard", Map.of(), null, null)).thenReturn(plan);
        when(dashboards.compile(plan, prepared, true, "Bearer current")).thenReturn(plan);
        var service = new CreationTaskPlanning(planner, contexts, catalogue, devices, dashboards, new PlanningInputGuard());
        var result = service.prepare(user, "dashboard", Map.of(), null, null, "Bearer current");
        assertThat(result.context().refs()).extracting(AuthorizedPlanningContext.ResourceRef::resourceId)
                .containsExactly(selected, source);
        assertThat(result.context().refs().get(1).summary()).containsEntry("deviceId", selected);
        verifyNoInteractions(devices);
        verify(planner, times(1)).plan(user, "dashboard", Map.of(), null, null);
    }
}
