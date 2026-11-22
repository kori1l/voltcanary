package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

class DashboardCompositionCompilerCapabilityTest {

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void revalidatesExactDataIdentityAndDisclosesTextHistoryAsAList(boolean usesDataRef) {
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboard");
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String device = UUID.randomUUID().toString(),
            source = UUID.randomUUID().toString();
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var context = new AuthorizedPlanningContext.Prepared(
            scope,
            Map.of(),
            List.of(
                new AuthorizedPlanningContext.ResourceRef(
                    "dev",
                    "device",
                    device,
                    Map.of("name", "Bus")
                ),
                new AuthorizedPlanningContext.ResourceRef(
                    "source",
                    "source",
                    source,
                    Map.of("deviceId", device)
                ),
                new AuthorizedPlanningContext.ResourceRef(
                    "data1",
                    "dashboard-data",
                    "exact-data-identity",
                    Map.of(
                        "deviceId",
                        device,
                        "sourceId",
                        source,
                        "family",
                        "RECORDED_VEHICLE_FIELD",
                        "key",
                        "observed.text",
                        "valueType",
                        "string",
                        "views",
                        List.of(
                            Map.of(
                                "widgetType",
                                "vehicle-timeline",
                                "visualizations",
                                List.of("list")
                            )
                        )
                    )
                )
            )
        );
        Map<String, Object> panel = usesDataRef
            ? Map.of(
                  "key",
                  "text",
                  "type",
                  "vehicle-timeline",
                  "dataRef",
                  "data1",
                  "visualization",
                  "list"
              )
            : Map.of(
                  "key",
                  "text",
                  "type",
                  "vehicle-timeline",
                  "vehicleField",
                  "observed.text",
                  "sourceRef",
                  "source",
                  "deviceRefs",
                  List.of("dev"),
                  "visualization",
                  "list"
              );
        var action = new AgentAction(
            UUID.randomUUID(),
            AgentTool.CREATE_DASHBOARD,
            "Review",
            "Review",
            true,
            Map.of("name", "QA", "deviceRefs", List.of("dev"), "widgets", List.of(panel))
        );
        var plan = new AgentPlan(
            UUID.randomUUID(),
            scope.userId(),
            "Create",
            "Review",
            "Review",
            List.of(),
            List.of(action),
            Instant.now()
        );
        server
            .expect(requestTo("http://dashboard/dashboards/composition/prepare"))
            .andExpect(header("Authorization", "auth"))
            .andExpect(
                content().json(
                    "{\"widgets\":[{\"key\":\"text\",\"vehicleField\":\"observed.text\"" +
                        (usesDataRef ? ",\"dataChoiceId\":\"exact-data-identity\"" : "") +
                        "}]}"
                )
            )
            .andRespond(
                withSuccess(
                    "{\"status\":\"READY\",\"digest\":\"digest\",\"definition\":{\"name\":\"QA\",\"widgets\":[]},\"devices\":[]}",
                    MediaType.APPLICATION_JSON
                )
            );
        var result = new DashboardCompositionCompiler(
            properties,
            builder,
            new ObjectMapper()
        ).compile(plan, context, action, "auth");
        assertThat(result.explanation()).contains("Текстовые значения показаны списком");
        assertThat(result.actions()).hasSize(1);
        server.verify();
    }
}
