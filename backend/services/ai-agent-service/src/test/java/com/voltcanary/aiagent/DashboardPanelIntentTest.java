package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.DashboardCreationCompiler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashboardPanelIntentTest {
    @Test void frozenRussianPanelFixturesAcceptOnlyBoundedCatalogueSelections() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/evaluation/dashboard-layout-intent-ru-v2.json"));
        assertThat(fixture.path("promptVersion").asText()).isEqualTo("creation-v5");
        var user = UUID.randomUUID();
        var template = new AuthorizedPlanningContext.ResourceRef("template-ref", "template", "refrigeration",
                Map.of("name", "Refrigeration", "version", 1, "panels", List.of(Map.of("key", "temperature-series"), Map.of("key", "temperature"))));
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user, UUID.randomUUID()), Map.of(),
                List.of(template, new AuthorizedPlanningContext.ResourceRef("device-ref", "device", UUID.randomUUID().toString(), Map.of("name", "Fridge"))));
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var compiler = new DashboardCreationCompiler(properties, builder, mapper);
        for (var row : fixture.path("cases")) {
            boolean ready = row.path("ready").asBoolean();
            var fields = Map.<String, Object>of("name", "Обзор", "templateRef", "template-ref", "templateVersion", 1,
                    "deviceRefs", List.of("device-ref"), "panelLayout", mapper.convertValue(row.path("panelLayout"), List.class));
            var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true, fields);
            var plan = new AgentPlan(UUID.randomUUID(), user, row.path("message").asText(), "Draft", "Review", List.of(), List.of(action), Instant.now());
            if (ready) server.expect(requestTo("http://dashboards/dashboard-templates/refrigeration/prepare"))
                    .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"fixture\",\"definition\":{\"widgets\":[]},\"devices\":[]}", MediaType.APPLICATION_JSON));
            assertThat(compiler.compile(plan, context, true, "Bearer current").actions()).as(row.path("id").asText()).hasSize(ready ? 1 : 0);
            server.verify();
            server.reset();
        }
    }

    @Test void fullPanelIntentReachesOwningPreparationAndExactDefinitionIsFrozen() {
        UUID user = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        var panels = List.of(Map.<String, Object>of("key", "temperature-series", "width", "FULL", "height", 6));
        var template = new AuthorizedPlanningContext.ResourceRef("template-ref", "template", "refrigeration",
                Map.of("name", "Refrigeration", "version", 1, "panels", List.of(Map.of("key", "temperature-series"))));
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user, UUID.randomUUID()), Map.of(),
                List.of(template, new AuthorizedPlanningContext.ResourceRef("device-ref", "device", device, Map.of("name", "Fridge"))));
        var fields = Map.<String, Object>of("name", "Обзор", "templateRef", "template-ref", "templateVersion", 1,
                "deviceRefs", List.of("device-ref"), "panelLayout", panels);
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Draft", "Review", true, fields);
        var plan = new AgentPlan(UUID.randomUUID(), user, "Создай «Обзор», удали события, график на всю ширину", "Draft", "Review", List.of(), List.of(action), Instant.now());
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var compiler = new DashboardCreationCompiler(properties, builder, new ObjectMapper());
        server.expect(requestTo("http://dashboards/dashboard-templates/refrigeration/prepare"))
                .andExpect(content().json("{\"panelLayout\":[{\"key\":\"temperature-series\",\"width\":\"FULL\",\"height\":6}]}"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"layout-digest\",\"definition\":{\"name\":\"Обзор\",\"widgets\":[],\"layouts\":{\"desktop\":[]}},\"devices\":[]}", MediaType.APPLICATION_JSON));
        var compiled = compiler.compile(plan, context, true, "Bearer current");
        assertThat(compiled.actions()).hasSize(1);
        assertThat(compiled.actions().getFirst().parameters()).containsEntry("prepareDigest", "layout-digest");
        assertThat(((Map<?, ?>) compiled.actions().getFirst().parameters().get("prepareIntent")).get("panelLayout")).isEqualTo(panels);
        server.verify();
        var invalid = new java.util.LinkedHashMap<>(fields);
        invalid.put("panelLayout", List.of(Map.of("key", "temperature-series", "x", 0)));
        var bad = new AgentPlan(plan.id(), user, plan.userMessage(), "Bad", "Review", List.of(),
                List.of(new AgentAction(action.id(), AgentTool.CREATE_DASHBOARD, "Bad", "Bad", true, invalid)), Instant.now());
        assertThat(compiler.compile(bad, context, true, "Bearer current").actions()).isEmpty();
        invalid.put("panelLayout", panels);
        invalid.put("templateVersion", 1.5);
        var fractional = new AgentPlan(plan.id(), user, plan.userMessage(), "Bad", "Review", List.of(),
                List.of(new AgentAction(action.id(), AgentTool.CREATE_DASHBOARD, "Bad", "Bad", true, invalid)), Instant.now());
        assertThat(compiler.compile(fractional, context, true, "Bearer current").actions()).isEmpty();
    }
}
