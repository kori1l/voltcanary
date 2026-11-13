package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardOperationNameCheckpointTest {
    @Test void aGroundedOperationNameIsCheckpointedBeforeUnrelatedPreparationFails() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER");
        var planner = mock(ActionPlanner.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var catalogue = mock(DashboardPlanningContext.class);
        var compiler = mock(DashboardCreationCompiler.class);
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID()),
                Map.of("dashboardPlanningProtocol", DashboardOperationProtocol.VERSION), List.of());
        when(contexts.prepare(eq(user), eq("auth"), anyString(), anyMap())).thenReturn(prepared);
        when(catalogue.enrich(eq(prepared), anyMap(), eq("auth"))).thenReturn(prepared);
        var parameters = Map.<String, Object>of("protocol", DashboardOperationProtocol.VERSION, "operations", List.of(
                Map.of("op", "rename_dashboard", "id", "name", "name", "Мой трамвай"),
                Map.of("op", "show_current", "id", "speed", "dataRef", "unavailable")));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Review", "Review", true, parameters);
        var draft = new AgentPlan(UUID.randomUUID(), user.userId(), "Мой трамвай", "Review", "Review", List.of(), List.of(action), Instant.now());
        when(planner.plan(eq(user), anyString(), anyMap(), isNull(), isNull())).thenReturn(draft);
        when(compiler.explicitName(anyString(), any(), eq("Мой трамвай"))).thenReturn(true);
        when(compiler.compile(any(), any(), eq(true), eq("auth"))).thenThrow(new DashboardPreparationRejected("Unavailable data"));
        var planning = new CreationTaskPlanning(planner, contexts, catalogue, mock(DeviceCreationCompiler.class), compiler, new PlanningInputGuard());
        var names = new ArrayList<String>();
        var result = planning.prepare(user, "Мой трамвай", Map.of("taskKind", "DASHBOARD"), null, null, "auth", null, names::add);
        assertThat(names).contains("Мой трамвай");
        assertThat(result.context().context()).containsEntry("name", "Мой трамвай");
        assertThat(result.plan().actions()).isEmpty();
    }
}
