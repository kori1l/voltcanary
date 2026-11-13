package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.llm.*;
import com.voltcanary.aiagent.model.*;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DashboardIntentFixtureTest {
    @Test void explicitTemplateClarificationOverridesOnlyTheEarlierTemplatePreference() {
        var mapper = new ObjectMapper();
        UUID user=UUID.randomUUID();String device=UUID.randomUUID().toString();
        var refs=List.of(new AuthorizedPlanningContext.ResourceRef("equipment", "device", device, Map.<String,Object>of("name","Fridge")),
                new AuthorizedPlanningContext.ResourceRef("old-template","template","my-devices",Map.<String,Object>of("name","My devices","version",1)),
                new AuthorizedPlanningContext.ResourceRef("new-template","template","refrigeration",Map.<String,Object>of("name","Refrigeration","version",1)));
        var fields=Map.<String,Object>of("name","Обзор","templateRef","new-template","templateVersion",1,"deviceRefs",List.of("equipment"));
        var action=new AgentAction(UUID.randomUUID(),AgentTool.CREATE_DASHBOARD,"Draft","Review",true,fields);
        String original="Создай «Обзор» по шаблону My devices";
        var plan=new AgentPlan(UUID.randomUUID(),user,original+"\nУточнение: используй шаблон Refrigeration","Review","Review",List.of(),List.of(action),Instant.now());
        var builder=com.voltcanary.common.web.JacksonRestClients.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var properties=new AiAgentProperties();properties.getServices().setDashboardBaseUrl("http://dashboards");
        var compiler=new DashboardCreationCompiler(properties,builder,mapper);
        var context=new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user,UUID.randomUUID()),
                Map.of("clarificationAnswers",List.of("используй шаблон Refrigeration")),refs);
        server.expect(requestTo("http://dashboards/dashboard-templates/refrigeration/prepare"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"new\",\"definition\":{\"name\":\"Обзор\",\"defaultContext\":{\"time\":{\"preset\":\"LAST_24_HOURS\"}},\"widgets\":[]},\"devices\":[]}",MediaType.APPLICATION_JSON));
        assertThat(compiler.compile(plan,context,true,"Bearer current").actions()).hasSize(1);
        server.verify();
        var nameOnly=new AuthorizedPlanningContext.Prepared(context.scope(),Map.of("clarificationAnswers",List.of("Название")),refs);
        assertThat(compiler.compile(plan,nameOnly,true,"Bearer current").actions()).isEmpty();
    }
    @Test void frozenRussianProviderIntentsCompileOnlyAuthorizedTemplateCommands() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/evaluation/dashboard-intent-ru-v1.json"));
        assertThat(fixture.path("cases").size()).isEqualTo(40);
        var actor = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER");
        String device = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
        List<AuthorizedPlanningContext.ResourceRef> refs = new ArrayList<>();
        refs.add(new AuthorizedPlanningContext.ResourceRef("device-ref", "device", device, Map.of("name", "Fixture fridge", "type", "FRIDGE")));
        Map<String, String> labels = Map.of("my-devices", "My devices", "refrigeration", "Refrigeration", "shift", "Shift overview",
                "technician", "Technician", "authorized-cohort", "Authorized cohort", "energy", "Energy");
        labels.forEach((id, name) -> refs.add(new AuthorizedPlanningContext.ResourceRef("template-" + id, "template", id, Map.of("name", name, "version", 1))));
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(actor.userId(), UUID.randomUUID()), Map.of(), refs);
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties(); properties.getServices().setDashboardBaseUrl("http://dashboards");
        var compiler = new DashboardCreationCompiler(properties, builder, mapper);
        var sanitizer = new LlmPlanSanitizer();
        for (var row : fixture.path("cases")) {
            boolean clear = row.path("group").asText().equals("clear");
            String message = row.path("message").asText();
            String template = clear ? row.path("expected").path("templateId").asText() : "my-devices";
            var quotedName = java.util.regex.Pattern.compile("«([^»]+)»").matcher(message);
            String name = clear ? row.path("expected").path("name").asText() : quotedName.find() ? quotedName.group(1) : "Неуказанное";
            var fields = Map.<String, Object>of("name", name, "templateRef", "template-" + template, "templateVersion", 1,
                    "deviceRefs", List.of("device-ref"), "period", "LAST_24_HOURS");
            var fallback = new AgentPlan(UUID.randomUUID(), actor.userId(), message, "Fallback", "Clarify", List.of(), List.of(), Instant.now());
            var draft = new LlmPlanDraft("OPENAI", "fixture-small-model", "Review", "Review",
                    List.of(new LlmActionDraft("CREATE_DASHBOARD", "Private draft", "Review", fields)));
            if (clear) {
                var response = mapper.createObjectNode().put("status", "READY").put("digest", "prepared-digest");
                var definition = response.putObject("definition").put("name", name);
                definition.putObject("defaultContext").putObject("time").put("preset", "LAST_24_HOURS");
                definition.putArray("widgets").addObject().put("id", "power").put("title", "Measured power");
                response.putArray("devices").addObject().put("id", device).putArray("sourceIds").add(source);
                server.expect(requestTo("http://dashboards/dashboard-templates/" + template + "/prepare"))
                        .andExpect(header("Authorization", "Bearer current"))
                        .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            }
            var plan = compiler.compile(sanitizer.sanitize(actor, message, draft, fallback), context, true, "Bearer current");
            assertThat(plan.actions().size()).as(row.path("id").asText()).isEqualTo(clear ? 1 : 0);
            if (clear) {
                var parameters = plan.actions().getFirst().parameters();
                assertThat(parameters).containsEntry("templateId", template).containsEntry("prepareDigest", "prepared-digest");
                var frozen = (Map<?, ?>) parameters.get("prepareIntent");
                assertThat(frozen.get("deviceIds")).isEqualTo(List.of(device));
                assertThat(frozen.get("sourceIds")).isEqualTo(List.of(source));
            }
            server.verify(); server.reset();
        }
    }

    @Test void modelOwnedLayoutsAndForeignTaskRefsRemainNonExecutable() {
        var properties = new AiAgentProperties();
        var compiler = new DashboardCreationCompiler(properties, com.voltcanary.common.web.JacksonRestClients.builder(), new ObjectMapper());
        UUID user = UUID.randomUUID();
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user, UUID.randomUUID()), Map.of(), List.of());
        var plan = new AgentPlan(UUID.randomUUID(), user, "Создай дашборд «Обзор»", "Review", "Review", List.of(),
                List.of(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Create", "Review", true,
                        Map.of("name", "Обзор", "definition", Map.of("widgets", List.of())))), Instant.now());
        assertThat(compiler.compile(plan, context, true, "Bearer current").actions()).isEmpty();
    }
}
