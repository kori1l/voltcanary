package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeviceSetupExecutorTest {
    private final UUID id = UUID.randomUUID();
    private final AiAgentProperties properties = new AiAgentProperties();
    private final RestClient.Builder builder = com.voltcanary.common.web.JacksonRestClients.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private DeviceSetupExecutor executor() {
        properties.getServices().setDeviceBaseUrl("http://devices");
        properties.getServices().setHslConnectorBaseUrl("http://hsl");
        return new DeviceSetupExecutor(builder, properties, java.util.List.of(new com.voltcanary.aiagent.adapter.HslDeviceIntegration(builder, properties)));
    }

    private AgentAction action(boolean connection) {
        return new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "create", "create", true,
                connection ? Map.of("name", "Bus", "type", "VEHICLE", "connection", Map.of("provider", "HSL_HFP", "mode", "bus", "vehicleKey", "0012-02512"))
                        : Map.of("name", "Fridge", "type", "FRIDGE"));
    }

    @Test void ordinaryDeviceCreationKeepsItsExistingContract() {
        var executor = executor();
        server.expect(requestTo("http://devices/devices")).andExpect(header("Authorization", "Bearer owner"))
                .andExpect(header("Idempotency-Key", "stable-key")).andExpect(content().json("{\"name\":\"Fridge\",\"type\":\"FRIDGE\"}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        assertThat(executor.execute(action(false), "Bearer owner", "stable-key")).containsEntry("setupStatus", "SOURCE_NOT_CONNECTED");
        server.verify();
    }

    @Test void reviewedConnectionReportsWaitingInsteadOfInventingReadiness() {
        properties.setDeviceSetupEnabled(true);
        var executor = executor();
        server.expect(requestTo("http://hsl/availability")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://devices/devices")).andExpect(header("Idempotency-Key", "stable-key"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://hsl/bindings")).andExpect(jsonPath("$.deviceId").value(id.toString()))
                .andRespond(withSuccess("{\"deviceId\":\"" + id + "\",\"sourceId\":\"hsl-bus-0012-02512\",\"setupStatus\":\"WAITING_FOR_DATA\"}", MediaType.APPLICATION_JSON));
        assertThat(executor.execute(action(true), "Bearer owner", "stable-key")).containsEntry("setupStatus", "WAITING_FOR_DATA").containsEntry("deviceId", id.toString());
        server.verify();
    }

    @Test void unavailableIntegrationDoesNotCreateAnEmptyDevice() {
        properties.setDeviceSetupEnabled(true);
        var executor = executor();
        server.expect(requestTo("http://hsl/availability")).andRespond(withServiceUnavailable());
        var action = action(true);
        var result = new ActionExecutionBoundary().run(action, new java.util.HashMap<>(), () -> executor.execute(action, "Bearer owner", "stable-key"));
        assertThat(result.status()).isEqualTo("UNKNOWN");
        server.verify();
    }
}
