package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.DashboardCreationCompiler;
import com.voltcanary.aiagent.service.DashboardCreationExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashboardCompositionIntentTest {
    @Test void rejectedOperationPreparationReturnsTypedDiagnosticsRatherThanAnEmptyActionCrash() {
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        UUID owner = UUID.randomUUID();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(owner, UUID.randomUUID()),
                Map.of("name", "Трамвай", "dashboardPlanningProtocol", "dashboard-operations-v1", "selectedDashboardDeviceRefs", List.of("device")), List.of(
                new AuthorizedPlanningContext.ResourceRef("device", "device", "device-id", Map.of("name", "Tram")),
                new AuthorizedPlanningContext.ResourceRef("source", "source", "source-id", Map.of()),
                new AuthorizedPlanningContext.ResourceRef("speed", "dashboard-data", "choice", Map.of("family", "RECORDED_VEHICLE_FIELD",
                        "deviceId", "device-id", "sourceId", "source-id", "key", "speedKmh", "views",
                        List.of(Map.of("widgetType", "vehicle-value", "visualizations", List.of("number")))))));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Draft", true,
                Map.of("protocol", "dashboard-operations-v1", "operations", List.of(Map.of("op", "show_current", "id", "s", "dataRef", "speed"))));
        var plan = new AgentPlan(UUID.randomUUID(), owner, "Создай дашборд «Трамвай»", "Draft", "Draft", List.of(), List.of(action), Instant.now());
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andRespond(withSuccess("{\"status\":\"NEEDS_INPUT\",\"diagnostics\":[\"Unsupported example\"]}", MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DashboardCreationCompiler(properties, builder, mapper)
                .compile(plan, context, true, "Bearer owner"))
                .isInstanceOf(com.voltcanary.aiagent.service.DashboardPreparationRejected.class).hasMessageContaining("Unsupported example");
        server.verify();
    }

    @Test void semanticReviewUsesOrdinaryPreparationAndExecutesTheExactFrozenDefinition() {
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        UUID owner = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(owner, UUID.randomUUID()),
                Map.of("name", "Трамвай", "dashboardPlanningProtocol", "dashboard-operations-v1", "dashboardTurnId", "persisted-turn",
                        "selectedDashboardDeviceRefs", List.of("device-ref")), List.of(
                new AuthorizedPlanningContext.ResourceRef("device-ref", "device", device, Map.of("name", "Tram")),
                new AuthorizedPlanningContext.ResourceRef("source-ref", "source", source, Map.of()),
                new AuthorizedPlanningContext.ResourceRef("data-ref", "dashboard-data", "choice", Map.of("deviceId", device,
                        "sourceId", source, "family", "RECORDED_VEHICLE_FIELD", "key", "doors", "label", "Двери",
                        "views", List.of(Map.of("widgetType", "vehicle-value", "visualizations", List.of("number")))))));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Review", "Review", true,
                Map.of("protocol", "dashboard-operations-v1", "operations", List.of(Map.of("op", "show_current", "id", "doors", "dataRef", "data-ref"))));
        var plan = new AgentPlan(UUID.randomUUID(), owner, "Создай дашборд Трамвай", "Review", "Review", List.of(), List.of(action), Instant.now());
        String prepared = "{\"status\":\"READY\",\"digest\":\"semantic-digest\",\"definition\":{\"name\":\"Трамвай\",\"widgets\":[{\"id\":\"stable\",\"type\":\"vehicle-value\"}]},\"devices\":[]}";
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andExpect(header("Authorization", "Bearer owner"))
                .andExpect(content().json("{\"name\":\"Трамвай\",\"deviceIds\":[\"" + device + "\"]}"))
                .andRespond(withSuccess(prepared, MediaType.APPLICATION_JSON));
        var reviewed = new DashboardCreationCompiler(properties, builder, mapper).compile(plan, context, true, "Bearer owner").actions().getFirst();
        assertThat(reviewed.parameters()).containsEntry("planningProtocol", "dashboard-operations-v1").containsEntry("prepareDigest", "semantic-digest");
        assertThat(reviewed.parameters()).containsKeys("operationChanges", "expectedOutcomes");
        server.verify();
        server.reset();
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andExpect(content().json(mapper.valueToTree(reviewed.parameters().get("prepareIntent")).toString()))
                .andRespond(withSuccess(prepared, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dashboards/dashboards"))
                .andExpect(content().json(mapper.valueToTree(Map.of("definition", reviewed.parameters().get("definition"))).toString()))
                .andRespond(withSuccess("{\"id\":\"created\",\"publishedRevision\":0}", MediaType.APPLICATION_JSON));
        assertThat(new DashboardCreationExecutor(properties, builder).execute(reviewed, "Bearer owner", "semantic-action")).containsEntry("setupStatus", "PRIVATE_DRAFT");
        server.verify();
    }
    @Test void fillsAnOmittedRequiredNameFromRememberedTaskValues() {
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        UUID owner = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(owner, UUID.randomUUID()),Map.of("name","Мой обзор"),
                List.of(new AuthorizedPlanningContext.ResourceRef("device-ref","device",device,Map.of("name","Bus"))));
        var action = new AgentAction(UUID.randomUUID(),AgentTool.CREATE_DASHBOARD,"Review","Review",true,
                Map.of("period","LAST_24_HOURS","deviceRefs",List.of("device-ref"),"widgets",List.of(Map.of("key","map","type","vehicle-map","title","Map"))));
        var plan = new AgentPlan(UUID.randomUUID(),owner,"Измени макет дашборда","Review","Review",List.of(),List.of(action),Instant.now());
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andExpect(content().json("{\"name\":\"Мой обзор\"}"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"remembered\",\"definition\":{\"name\":\"Мой обзор\",\"widgets\":[]},\"devices\":[]}",MediaType.APPLICATION_JSON));
        assertThat(new DashboardCreationCompiler(properties,builder,new ObjectMapper()).compile(plan,context,true,"Bearer owner").actions()).hasSize(1);
        server.verify();
    }
    @Test void freezesMapAndChartCompositionAndExecutesExactlyTheReviewedDefinition() {
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        var compiler = new DashboardCreationCompiler(properties, builder, mapper);
        var executor = new DashboardCreationExecutor(properties, builder);
        UUID owner = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(owner, UUID.randomUUID()), Map.of(),
                List.of(new AuthorizedPlanningContext.ResourceRef("device-ref", "device", device, Map.of("name", "Bus"))));
        var fields = Map.<String, Object>of("name", "Автобус", "period", "LAST_24_HOURS", "deviceRefs", List.of("device-ref"), "widgets", List.of(
                Map.of("key", "map", "type", "vehicle-map", "title", "Карта", "width", "FULL", "height", 6),
                Map.of("key", "speed", "type", "time-series", "title", "Скорость", "metric", "speedKmh", "unit", "km/h", "width", "FULL", "height", 4)));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true, fields);
        var plan = new AgentPlan(UUID.randomUUID(), owner, "Создай «Автобус» с картой и графиком скорости", "Draft", "Review", List.of(), List.of(action), Instant.now());
        String prepared = "{\"status\":\"READY\",\"digest\":\"composition-digest\",\"definition\":{\"name\":\"Автобус\",\"widgets\":[{\"id\":\"map\",\"type\":\"vehicle-map\"},{\"id\":\"speed\",\"type\":\"time-series\"}]},\"devices\":[]}";
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare"))
                .andExpect(header("Authorization", "Bearer owner"))
                .andExpect(content().json("{\"deviceIds\":[\"" + device + "\"],\"widgets\":[{\"key\":\"map\",\"type\":\"vehicle-map\"},{\"key\":\"speed\",\"type\":\"time-series\"}]}"))
                .andRespond(withSuccess(prepared, MediaType.APPLICATION_JSON));
        var compiled = compiler.compile(plan, context, true, "Bearer owner");
        assertThat(compiled.actions()).hasSize(1);
        var reviewed = compiled.actions().getFirst();
        assertThat(reviewed.parameters()).containsEntry("compositionVersion", 1).containsEntry("prepareDigest", "composition-digest").doesNotContainKey("templateId");
        server.verify();
        server.reset();
        server.expect(requestTo("http://dashboards/dashboards/composition/prepare")).andRespond(withSuccess(prepared, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dashboards/dashboards"))
                .andExpect(content().json(mapper.valueToTree(Map.of("definition", reviewed.parameters().get("definition"))).toString()))
                .andRespond(withSuccess("{\"id\":\"created\",\"publishedRevision\":0}", MediaType.APPLICATION_JSON));
        assertThat(executor.execute(reviewed, "Bearer owner", "action-key")).containsEntry("route", "/dashboards/created/edit");
        server.verify();
    }

    @Test void rejectsRawQueriesAndForeignDeviceRefsBeforeCallingTheOwningService() {
        var properties = new AiAgentProperties();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()), Map.of(), List.of());
        var compiler = new DashboardCreationCompiler(properties, com.voltcanary.common.web.JacksonRestClients.builder(), new ObjectMapper());
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Bad", "Bad", true,
                Map.of("name", "Автобус", "deviceRefs", List.of("foreign-ref"), "widgets", List.of(Map.of("key", "map", "type", "vehicle-map", "query", Map.of("url", "https://elsewhere")))));
        var plan = new AgentPlan(UUID.randomUUID(), context.scope().userId(), "Создай «Автобус»", "Bad", "Bad", List.of(), List.of(action), Instant.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> compiler.compile(plan, context, true, "Bearer owner"))
                .isInstanceOf(com.voltcanary.aiagent.service.DashboardPreparationRejected.class);
    }
}
