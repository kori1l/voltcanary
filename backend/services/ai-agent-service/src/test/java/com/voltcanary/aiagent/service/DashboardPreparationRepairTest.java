package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashboardPreparationRepairTest {
    @Test void actualPreparationDiagnosticTriggersSameTaskRepairAndExplicitNameOutranksSuggestedDefault() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "tram@example.test", "USER");
        var scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        var ref = new AuthorizedPlanningContext.ResourceRef("tram-ref", "device", UUID.randomUUID().toString(), Map.of("name", "Tram"));
        var contexts = mock(AuthorizedPlanningContext.class);
        when(contexts.prepare(eq(user), eq("Bearer owner"), any(), any())).thenAnswer(call ->
                new AuthorizedPlanningContext.Prepared(scope, call.getArgument(3), List.of(ref)));
        var catalogue = mock(DashboardPlanningContext.class);
        when(catalogue.requested(any())).thenReturn(true);
        when(catalogue.enrich(any(), any(), eq("Bearer owner"))).thenAnswer(call -> call.getArgument(0));
        var planner = mock(ActionPlanner.class);
        var draft = new AgentPlan(UUID.randomUUID(), user.userId(), "Назови Мой любимый трамвай", "Draft", "Review", List.of(),
                List.of(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true,
                        Map.of("name", "Wrong suggested default", "deviceRefs", List.of("tram-ref"), "widgets", List.of(
                                Map.of("key", "doors", "type", "vehicle-timeline", "title", "Двери", "vehicleField", "transport.doorsOpen", "visualization", "line"))))), Instant.now());
        when(planner.plan(eq(user), any(), any(), isNull(), isNull())).thenReturn(draft);
        var properties = new AiAgentProperties();properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andExpect(content().json("{\"name\":\"Мой любимый трамвай\"}"))
                .andRespond(withSuccess("{\"status\":\"NEEDS_INPUT\",\"diagnostics\":[\"Incompatible field parameters\"]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"verified\",\"definition\":{\"name\":\"Мой любимый трамвай\"},\"devices\":[]}",MediaType.APPLICATION_JSON));
        var compiler = new DashboardCreationCompiler(properties,builder,new ObjectMapper());
        var service = new CreationTaskPlanning(planner,contexts,catalogue,mock(DeviceCreationCompiler.class),compiler,new PlanningInputGuard());
        var result = service.prepare(user,draft.userMessage(),Map.of("clarificationAnswers",List.of("Назови Мой любимый трамвай")),null,null,"Bearer owner");
        assertThat(result.plan().actions()).hasSize(1);
        assertThat(result.context().context()).containsEntry("name","Мой любимый трамвай");
        var captured = ArgumentCaptor.forClass(Map.class);
        verify(planner,times(2)).plan(eq(user),any(),captured.capture(),isNull(),isNull());
        assertThat(captured.getAllValues().get(1).get("compositionRepair").toString()).contains("Incompatible field parameters");
        server.verify();
    }
    @Test void repairIsBoundedAndFailureKeepsTheNameAndPendingChangeForTheNextReply() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "tram@example.test", "USER");
        var scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        var input = Map.<String,Object>of("name", "Мой любимый трамвай", "originalDashboardRequest", "Создай дашборд трамвая",
                "clarificationAnswers", List.of("Перемести карточки вверх и сделай график дверей"),
                "previousDashboardIntent", Map.of("name", "Мой любимый трамвай", "widgets", List.of(Map.of("key", "map"))));
        var contexts = mock(AuthorizedPlanningContext.class);
        when(contexts.prepare(eq(user), eq("Bearer owner"), any(), any())).thenAnswer(call -> new AuthorizedPlanningContext.Prepared(scope,call.getArgument(3),List.of()));
        var catalogue = mock(DashboardPlanningContext.class);when(catalogue.requested(any())).thenReturn(true);
        when(catalogue.enrich(any(),any(),eq("Bearer owner"))).thenAnswer(call -> call.getArgument(0));
        var plan = new AgentPlan(UUID.randomUUID(),user.userId(),"Update dashboard","Update","Update",List.of(),List.of(
                new AgentAction(UUID.randomUUID(),AgentTool.CREATE_DASHBOARD,"Update","Update",true,Map.of("widgetEdits",List.of()))),Instant.now());
        var planner=mock(ActionPlanner.class);when(planner.plan(eq(user),any(),any(),isNull(),isNull())).thenReturn(plan);
        var compiler=mock(DashboardCreationCompiler.class);when(compiler.compile(any(),any(),eq(true),eq("Bearer owner"))).thenThrow(new DashboardPreparationRejected("Invalid vehicle metric"));
        var service=new CreationTaskPlanning(planner,contexts,catalogue,mock(DeviceCreationCompiler.class),compiler,new PlanningInputGuard());
        var result=service.prepare(user,plan.userMessage(),input,null,null,"Bearer owner");
        assertThat(result.plan().actions()).isEmpty();
        verify(planner,times(3)).plan(eq(user),any(),any(),isNull(),isNull());
        var remembered=new PlanClarifications(mock(PlanStore.class),contexts,new PlanningInputGuard()).remember(result.context(),result.plan(),input,2,UUID.randomUUID(),List.of());
        var state=(Map<?,?>)remembered.context().get("_clarification");var values=(Map<?,?>)state.get("input");
        assertThat(values.get("name")).isEqualTo("Мой любимый трамвай");
        assertThat(values.get("pendingDashboardChange")).isEqualTo("Перемести карточки вверх и сделай график дверей");
        assertThat(values.get("originalDashboardRequest")).isEqualTo("Создай дашборд трамвая");
    }
}
