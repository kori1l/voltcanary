package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.common.web.JacksonRestClients;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SimulatorToolContractTest {
    private final UUID device = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final String path = "http://simulator/simulation/devices/" + device;
    private final String authorization = "Bearer fixture";
    private final MockRestServiceServer server;
    private final ToolExecutor executor;

    SimulatorToolContractTest() {
        var builder = JacksonRestClients.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties();
        properties.getServices().setSimulatorBaseUrl("http://simulator");
        executor = new ToolExecutor(builder, properties, mock(DashboardCreationExecutor.class),
                new ActionExecutionBoundary(), new DeviceSetupExecutor(builder.clone(), properties, List.of()));
    }

    @Test
    void validatesExactYamlWithOnlyTheReadOnlyValidationEndpoint() {
        server.expect(requestTo(path + "/config/validate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", authorization))
                .andExpect(content().json("{\"yamlConfig\":\"profile: FRIDGE\\n\"}", true))
                .andRespond(withSuccess("{\"valid\":true}", MediaType.APPLICATION_JSON));
        assertEquals(Map.of("valid", true), execute(AgentTool.VALIDATE_SIMULATOR_YAML,
                Map.of("deviceId", device, "yamlConfig", "profile: FRIDGE\n")).get("validation"));
        server.verify();
    }

    @Test
    void savesSelectedSourceAndStartsAndStopsOnlyOnSeparateActions() {
        var source = UUID.randomUUID();
        server.expect(requestTo(path + "/config")).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", authorization)).andExpect(header("X-User-Id", user.toString()))
                .andExpect(content().json("""
                        {"dataSourceId":"%s","name":"simulator-simulator","profile":"FRIDGE","protocol":"HTTP","yamlConfig":"profile: FRIDGE"}
                        """.formatted(source), true))
                .andRespond(withSuccess("{\"dataSourceId\":\"" + source + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(path + "/start")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", authorization)).andExpect(header("X-User-Id", user.toString()))
                .andRespond(withSuccess("{\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(path + "/stop")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", authorization))
                .andRespond(withSuccess("{\"status\":\"STOPPED\"}", MediaType.APPLICATION_JSON));
        assertEquals(Map.of("dataSourceId", source.toString()), execute(AgentTool.CREATE_SIMULATOR_CONFIG,
                Map.of("deviceId", device, "dataSourceId", source, "yamlConfig", "profile: FRIDGE")).get("simulatorConfig"));
        assertEquals(Map.of("status", "RUNNING"), execute(AgentTool.START_SIMULATION, Map.of("deviceId", device)).get("simulationRun"));
        assertEquals(Map.of("status", "STOPPED"), execute(AgentTool.STOP_SIMULATION, Map.of("deviceId", device)).get("simulationRun"));
        server.verify();
    }

    private Map<String, Object> execute(AgentTool tool, Map<String, Object> parameters) {
        var action = new AgentAction(UUID.randomUUID(), tool, tool.name(), "Reviewed fixture", true, parameters);
        var result = executor.execute(action, authorization, user, new LinkedHashMap<>(), "fixture-key");
        assertEquals("SUCCESS", result.status(), result::error);
        return result.result();
    }
}
