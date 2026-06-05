package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.model.AgentAction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ToolExecutor {

    private final RestClient deviceClient;
    private final RestClient simulatorClient;

    public ToolExecutor(RestClient.Builder restClientBuilder, AiAgentProperties properties) {
        this.deviceClient = restClientBuilder.baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.simulatorClient = restClientBuilder.baseUrl(properties.getServices().getSimulatorBaseUrl()).build();
    }

    public ActionExecutionResponse execute(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs) {
        try {
            Map<String, Object> result = switch (action.tool()) {
                case CREATE_DEVICE -> createDevice(action, authorization);
                case CREATE_DATA_SOURCE -> createDataSource(action, authorization, outputs);
                case CREATE_SIMULATOR_CONFIG -> createSimulatorConfig(action, authorization, userId, outputs);
                case VALIDATE_SIMULATOR_YAML -> validateSimulatorYaml(action, outputs);
            };
            outputs.putAll(result);
            return new ActionExecutionResponse(action.id(), action.tool().name(), "SUCCESS", result, null);
        } catch (Exception exception) {
            return new ActionExecutionResponse(action.id(), action.tool().name(), "FAILED", Map.of(), exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDevice(AgentAction action, String authorization) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationId", action.parameters().get("locationId"));
        body.put("name", action.parameters().get("name"));
        body.put("type", action.parameters().get("type"));
        body.put("manufacturer", action.parameters().get("manufacturer"));
        body.put("model", action.parameters().get("model"));
        Map<String, Object> response = deviceClient.post()
                .uri("/devices")
                .header("Authorization", authorization)
                .body(body)
                .retrieve()
                .body(Map.class);
        return Map.of("deviceId", response.get("id"), "device", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDataSource(AgentAction action, String authorization, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId is required to create a data source");
        }
        Map<String, Object> body = Map.of(
                "sourceType", action.parameters().get("sourceType"),
                "protocol", action.parameters().get("protocol"),
                "externalId", action.parameters().get("externalId"));
        Map<String, Object> response = deviceClient.post()
                .uri("/devices/{deviceId}/data-sources", deviceId)
                .header("Authorization", authorization)
                .body(body)
                .retrieve()
                .body(Map.class);
        return Map.of("dataSourceId", response.get("id"), "dataSource", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createSimulatorConfig(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        Object dataSourceId = firstNonNull(action.parameters().get("dataSourceId"), outputs.get("dataSourceId"));
        if (deviceId == null || dataSourceId == null) {
            throw new IllegalArgumentException("deviceId and dataSourceId are required to create simulator config");
        }
        Map<String, Object> body = Map.of(
                "dataSourceId", dataSourceId,
                "name", action.parameters().get("name"),
                "profile", action.parameters().get("profile"),
                "protocol", action.parameters().get("protocol"),
                "yamlConfig", action.parameters().get("yamlConfig"));
        Map<String, Object> response = simulatorClient.put()
                .uri("/simulation/devices/{deviceId}/config", deviceId)
                .header("Authorization", authorization)
                .header("X-User-Id", userId.toString())
                .body(body)
                .retrieve()
                .body(Map.class);
        return Map.of("simulatorConfig", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateSimulatorYaml(AgentAction action, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"), new UUID(0, 0));
        Map<String, Object> response = simulatorClient.post()
                .uri("/simulation/devices/{deviceId}/config/validate", deviceId)
                .body(Map.of("yamlConfig", action.parameters().getOrDefault("yamlConfig", "")))
                .retrieve()
                .body(Map.class);
        return Map.of("validation", response);
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
