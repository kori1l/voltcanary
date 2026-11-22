package com.voltcanary.aiagent;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeviceExecutorBoundaryTest {
    @Test void separateHslSourceReusesConnectorReceiptAndNeverCreatesAnotherDevice() {
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties();
        properties.setDeviceSetupEnabled(true);
        properties.getServices().setHslConnectorBaseUrl("http://connector");
        String deviceId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        var connector = new com.voltcanary.aiagent.adapter.HslDeviceIntegration(builder.clone(), properties);
        var executor = new ToolExecutor(builder, properties, org.mockito.Mockito.mock(com.voltcanary.aiagent.service.DashboardCreationExecutor.class),
                new com.voltcanary.aiagent.service.ActionExecutionBoundary(),
                new com.voltcanary.aiagent.service.DeviceSetupExecutor(builder, properties, java.util.List.of(connector)));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DATA_SOURCE, "Connect", "Separate review", true,
                Map.of("provider", "HSL_HFP", "mode", "tram", "vehicleKey", "0040-00442", "deviceId", deviceId));
        var reconciliation = new com.voltcanary.aiagent.service.CreationReconciliation(executor);
        String request = "{\"mode\":\"tram\",\"vehicleKey\":\"0040-00442\",\"creationKey\":\"stable-source-key\"}";
        String binding = request.substring(0, request.length() - 1) + ",\"deviceId\":\"" + deviceId + "\"}";
        var user = UUID.randomUUID();
        for (boolean lostResponse : new boolean[]{true, false}) {
            server.expect(requestTo("http://connector/availability")).andExpect(header("Authorization", "Bearer current"))
                    .andExpect(content().json(request, true)).andRespond(withSuccess());
            server.expect(requestTo("http://connector/bindings")).andExpect(header("Authorization", "Bearer current"))
                    .andExpect(content().json(binding, true))
                    .andRespond(lostResponse ? withSuccess("unreadable-after-commit", MediaType.APPLICATION_JSON)
                            : withSuccess("{\"deviceId\":\"" + deviceId + "\",\"dataSourceId\":\"" + sourceId + "\"}", MediaType.APPLICATION_JSON));
        }
        assertThat(reconciliation.supports(action)).isTrue();
        var first = executor.execute(action, "Bearer current", user, new LinkedHashMap<>(), "stable-source-key");
        assertThat(first.status()).isEqualTo("UNKNOWN");
        var replay = reconciliation.reconcile(action, "Bearer current", user, new LinkedHashMap<>(), "stable-source-key");
        assertThat(replay.status()).isEqualTo("SUCCESS");
        assertThat(replay.result()).containsEntry("deviceId", deviceId).containsEntry("dataSourceId", sourceId);
        server.verify();
        assertThat(reconciliation.supports(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DATA_SOURCE, "Legacy", "Legacy", true,
                Map.of("deviceId", deviceId, "protocol", "MQTT")))).isFalse();
    }
    @Test void domainCommitWithUnreadableResponseIsUnknownAndReplaysTheSameKey() {
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties();
        properties.getServices().setDeviceBaseUrl("http://devices");
        var executor = new ToolExecutor(builder, properties, org.mockito.Mockito.mock(com.voltcanary.aiagent.service.DashboardCreationExecutor.class),
                new com.voltcanary.aiagent.service.ActionExecutionBoundary(),
                new com.voltcanary.aiagent.service.DeviceSetupExecutor(builder, properties, java.util.List.of()));
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "Create", "One device", true,
                Map.of("name", "Reviewed", "type", "FRIDGE", "locationName", "Display only"));
        server.expect(requestTo("http://devices/devices")).andExpect(header("Authorization", "Bearer current"))
                .andExpect(header("Idempotency-Key", "stable-action-key"))
                .andExpect(content().json("{\"name\":\"Reviewed\",\"type\":\"FRIDGE\"}", true))
                .andRespond(withSuccess("not-json-after-commit", MediaType.APPLICATION_JSON));
        var first = executor.execute(action, "Bearer current", UUID.randomUUID(), new LinkedHashMap<>(), "stable-action-key");
        assertThat(first.status()).isEqualTo("UNKNOWN");
        server.verify();
        server.reset();
        server.expect(requestTo("http://devices/devices")).andExpect(header("Idempotency-Key", "stable-action-key"))
                .andRespond(withSuccess("{\"id\":\"committed-device\",\"name\":\"Reviewed\"}", MediaType.APPLICATION_JSON));
        var result = executor.execute(action, "Bearer current", UUID.randomUUID(), new LinkedHashMap<>(), "stable-action-key");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.result()).containsEntry("deviceId", "committed-device").containsEntry("setupStatus", "SOURCE_NOT_CONNECTED");
        server.verify();
    }
}
