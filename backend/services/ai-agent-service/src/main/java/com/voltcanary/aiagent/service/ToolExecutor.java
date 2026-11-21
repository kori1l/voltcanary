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
    private final com.voltcanary.aiagent.vehicle.VehicleMonitoringExecutor vehicles;
    private final LegacyAlertOperations legacyAlerts;
    private final com.voltcanary.aiagent.nativeops.NativeTableExecutor nativeTables;
    private final RestClient simulatorClient;
    private final DashboardCreationExecutor dashboardCreation;
    private final ActionExecutionBoundary boundary;
    private final DeviceSetupExecutor deviceSetup;

    public ToolExecutor(RestClient.Builder restClientBuilder, AiAgentProperties properties, DashboardCreationExecutor dashboardCreation, ActionExecutionBoundary boundary, DeviceSetupExecutor deviceSetup) {
        this(restClientBuilder, properties, dashboardCreation, boundary, deviceSetup,
                new com.voltcanary.aiagent.nativeops.NativeTableExecutor(new com.voltcanary.aiagent.nativeops.NativeOwnerClient(restClientBuilder, properties), new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolExecutor(RestClient.Builder restClientBuilder, AiAgentProperties properties, DashboardCreationExecutor dashboardCreation,
            ActionExecutionBoundary boundary, DeviceSetupExecutor deviceSetup, com.voltcanary.aiagent.nativeops.NativeTableExecutor nativeTables) {
        this.vehicles = new com.voltcanary.aiagent.vehicle.VehicleMonitoringExecutor(new com.voltcanary.aiagent.vehicle.VehicleMonitoringOwner(restClientBuilder, properties), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        this.deviceClient = restClientBuilder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.legacyAlerts = new LegacyAlertOperations(restClientBuilder.clone().baseUrl(properties.getServices().getAlertBaseUrl()).build());
        this.simulatorClient = restClientBuilder.clone().baseUrl(properties.getServices().getSimulatorBaseUrl()).build();
        this.dashboardCreation = dashboardCreation;
        this.boundary = boundary;
        this.deviceSetup = deviceSetup;
        this.nativeTables = nativeTables;
    }

    public ActionExecutionResponse execute(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs,
            String idempotencyKey) {
        return boundary.run(action, outputs, () -> switch (action.tool()) {
                case UPDATE_VEHICLE_MONITORING -> vehicles.execute(action, authorization, userId, false);
                case CREATE_DEVICE -> deviceSetup.execute(action, authorization, idempotencyKey);
                case CREATE_NATIVE_TABLE -> nativeTables.execute(action, authorization);
                case APPLY_SOURCE_MAPPING -> nativeTables.executeMapping(action, authorization, false);
                case CREATE_DASHBOARD, UPDATE_DASHBOARD_DRAFT -> dashboardCreation.execute(action, authorization, idempotencyKey);
                case CREATE_DATA_SOURCE -> action.parameters().containsKey("provider")
                        ? deviceSetup.connectExisting(action, authorization, idempotencyKey) : createDataSource(action, authorization, outputs);
                case CREATE_SIMULATOR_CONFIG -> createSimulatorConfig(action, authorization, userId, outputs);
                case VALIDATE_SIMULATOR_YAML -> validateSimulatorYaml(action, authorization, outputs);
                case CREATE_ALERT_RULE -> legacyAlerts.createAlertRule(action, authorization);
                case UPDATE_ALERT_RULE -> legacyAlerts.updateAlertRule(action, authorization);
                case TEST_ALERT_RULE -> legacyAlerts.testAlertRule(action, authorization);
                case EXPLAIN_ALERT -> legacyAlerts.explainAlert(action, authorization);
                case OPEN_TELEMETRY_WINDOW -> legacyAlerts.openTelemetryWindow(action);
                case CREATE_DEMO_DEVICE -> createDemoDevice(action, authorization, userId);
                case START_SIMULATION -> startSimulation(action, authorization, userId, outputs);
                case STOP_SIMULATION -> stopSimulation(action, authorization, outputs);
        });
    }

    public ActionExecutionResponse reconcile(AgentAction action, String authorization, Map<String, Object> outputs, String key) {
        if (action.tool() == com.voltcanary.aiagent.model.AgentTool.UPDATE_VEHICLE_MONITORING)
            return boundary.run(action, outputs, () -> vehicles.execute(action, authorization, UUID.fromString(action.parameters().get("userId").toString()), true));
        if (action.tool() == com.voltcanary.aiagent.model.AgentTool.APPLY_SOURCE_MAPPING)
            return boundary.run(action, outputs, () -> nativeTables.executeMapping(action, authorization, true));
        return boundary.run(action, outputs, () -> action.tool() == com.voltcanary.aiagent.model.AgentTool.CREATE_NATIVE_TABLE
                ? nativeTables.reconcile(action, authorization) : dashboardCreation.reconcile(action, authorization, key));
    }

    public ActionExecutionResponse inspectNativeOutcome(AgentAction action, String authorization, Map<String, Object> outputs) {
        return boundary.run(action, outputs, () -> nativeTables.inspectOutcome(action, authorization));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDataSource(AgentAction action, String authorization, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        if (deviceId == null || deviceId.toString().isBlank()) {
            throw new IllegalArgumentException("deviceId is required to create a data source");
        }
        Map<String, Object> response = postDataSource(deviceId, dataSourceBody(action.parameters()), authorization);
        return resultWith("dataSourceId", response.get("id"), "dataSource", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createSimulatorConfig(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        Object dataSourceId = firstNonNull(action.parameters().get("dataSourceId"), outputs.get("dataSourceId"));
        if (deviceId == null || dataSourceId == null) {
            throw new IllegalArgumentException("deviceId and dataSourceId are required to create simulator config");
        }
        Map<String, Object> response = putSimulatorConfig(deviceId, simulatorConfigBody(action.parameters(), dataSourceId, null), authorization, userId);
        return Map.of("simulatorConfig", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateSimulatorYaml(AgentAction action, String authorization, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"), new UUID(0, 0));
        Map<String, Object> response = simulatorClient.post()
                .uri("/simulation/devices/{deviceId}/config/validate", deviceId)
                .header("Authorization", authorization)
                .body(Map.of("yamlConfig", action.parameters().getOrDefault("yamlConfig", "")))
                .retrieve()
                .body(Map.class);
        return Map.of("validation", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDemoDevice(AgentAction action, String authorization, UUID userId) {
        Map<String, Object> device = postDevice(deviceBody(action.parameters()), authorization);
        Object deviceId = device.get("id");
        String simulatorProtocol = stringValue(action.parameters().get("protocol"), "HTTP");
        String sourceId = stringValue(action.parameters().get("sourceId"), "sim-" + UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> dataSourceRequest = new LinkedHashMap<>();
        dataSourceRequest.put("sourceType", "SIMULATOR");
        dataSourceRequest.put("protocol", "MQTT".equalsIgnoreCase(simulatorProtocol) ? "MQTT" : "REST");
        dataSourceRequest.put("externalId", sourceId);
        Map<String, Object> dataSource = postDataSource(deviceId, dataSourceRequest, authorization);
        Object dataSourceId = dataSource.get("id");

        String sourceToken = null;
        if (!"MQTT".equalsIgnoreCase(simulatorProtocol)) {
            Map<String, Object> tokenResponse = deviceClient.post()
                    .uri("/devices/{deviceId}/data-sources/{dataSourceId}/token", deviceId, dataSourceId)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(Map.class);
            sourceToken = tokenResponse == null ? null : stringValue(tokenResponse.get("token"), null);
        }

        Map<String, Object> simulatorParameters = new LinkedHashMap<>(action.parameters());
        simulatorParameters.put("sourceId", sourceId);
        simulatorParameters.put("protocol", simulatorProtocol);
        simulatorParameters.put("yamlConfig", template(stringValue(action.parameters().get("profile"), "FRIDGE"), sourceId, authorization));
        Map<String, Object> simulatorConfig = putSimulatorConfig(
                deviceId,
                simulatorConfigBody(simulatorParameters, dataSourceId, sourceToken),
                authorization,
                userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("dataSourceId", dataSourceId);
        result.put("device", device);
        result.put("dataSource", dataSource);
        result.put("simulatorConfig", simulatorConfig);
        result.put("route", "/devices/" + encode(deviceId) + "?tab=telemetry");

        if (booleanValue(action.parameters().get("startImmediately"))) {
            Map<String, Object> run = startSimulationForDevice(deviceId, authorization, userId);
            result.put("simulationRun", run);
        }
        return result;
    }

    private Map<String, Object> startSimulation(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        if (deviceId == null || deviceId.toString().isBlank()) {
            throw new IllegalArgumentException("deviceId is required to start simulation");
        }
        return Map.of("simulationRun", startSimulationForDevice(deviceId, authorization, userId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stopSimulation(AgentAction action, String authorization, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"));
        if (deviceId == null || deviceId.toString().isBlank()) {
            throw new IllegalArgumentException("deviceId is required to stop simulation");
        }
        Map<String, Object> response = simulatorClient.post()
                .uri("/simulation/devices/{deviceId}/stop", deviceId)
                .header("Authorization", authorization)
                .retrieve()
                .body(Map.class);
        return Map.of("simulationRun", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postDevice(Map<String, Object> body, String authorization) {
        return deviceClient.post()
                .uri("/devices")
                .header("Authorization", authorization)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postDataSource(Object deviceId, Map<String, Object> body, String authorization) {
        return deviceClient.post()
                .uri("/devices/{deviceId}/data-sources", deviceId)
                .header("Authorization", authorization)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> putSimulatorConfig(Object deviceId, Map<String, Object> body, String authorization, UUID userId) {
        return simulatorClient.put()
                .uri("/simulation/devices/{deviceId}/config", deviceId)
                .header("Authorization", authorization)
                .header("X-User-Id", userId.toString())
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> startSimulationForDevice(Object deviceId, String authorization, UUID userId) {
        return simulatorClient.post()
                .uri("/simulation/devices/{deviceId}/start", deviceId)
                .header("Authorization", authorization)
                .header("X-User-Id", userId.toString())
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private String template(String profile, String sourceId, String authorization) {
        Map<String, Object> response = simulatorClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/simulation/templates/{profile}")
                        .queryParam("sourceId", sourceId)
                        .build(profile))
                .header("Authorization", authorization)
                .retrieve()
                .body(Map.class);
        return stringValue(response == null ? null : response.get("yamlConfig"), fallbackYaml(sourceId, profile));
    }

    private Map<String, Object> deviceBody(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "locationId", parameters.get("locationId"));
        body.put("name", stringValue(parameters.get("name"), "AI Created Device"));
        body.put("type", stringValue(parameters.get("type"), "SMART_PLUG"));
        body.put("manufacturer", stringValue(parameters.get("manufacturer"), "VoltCanary"));
        body.put("model", stringValue(parameters.get("model"), "AI-created"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("createdBy", "ai-agent");
        putIfPresent(metadata, "preset", parameters.get("preset"));
        body.put("metadata", metadata);
        return body;
    }

    private Map<String, Object> dataSourceBody(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceType", stringValue(parameters.get("sourceType"), "SIMULATOR"));
        body.put("protocol", stringValue(parameters.get("protocol"), "REST"));
        body.put("externalId", stringValue(parameters.get("externalId"), stringValue(parameters.get("sourceId"), "sim-" + UUID.randomUUID().toString().substring(0, 8))));
        return body;
    }

    private Map<String, Object> simulatorConfigBody(Map<String, Object> parameters, Object dataSourceId, String sourceToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataSourceId", dataSourceId);
        body.put("name", stringValue(parameters.get("name"), stringValue(parameters.get("sourceId"), "simulator") + "-simulator"));
        body.put("profile", stringValue(parameters.get("profile"), "FRIDGE"));
        body.put("protocol", stringValue(parameters.get("protocol"), "HTTP"));
        body.put("yamlConfig", stringValue(parameters.get("yamlConfig"), fallbackYaml(stringValue(parameters.get("sourceId"), "sim-fridge-001"), stringValue(parameters.get("profile"), "FRIDGE"))));
        putIfPresent(body, "sourceToken", sourceToken);
        return body;
    }

    private Map<String, Object> resultWith(String idKey, Object idValue, String objectKey, Object objectValue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(idKey, idValue);
        result.put(objectKey, objectValue);
        return result;
    }

    private void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            body.put(key, value);
        }
    }

    private Object required(Object value, String message) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String encode(Object value) {
        return java.net.URLEncoder.encode(value.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String fallbackYaml(String sourceId, String profile) {
        return """
                schemaVersion: 1
                simulator:
                  name: %s-simulator
                  profile: %s
                  mode: NORMAL
                  protocol: HTTP
                target:
                  sourceId: "%s"
                schedule:
                  interval: 5s
                  startDelay: 0s
                  duration: 30m
                  speed: 1.0
                metrics:
                  voltageV:
                    type: constant
                    value: 230.0
                  activePowerW:
                    type: cycle
                    offValue:
                      mean: 1.4
                    onValue:
                      mean: 120.0
                      noise: 8.0
                    cycle:
                      onDuration:
                        min: 8m
                        max: 16m
                      offDuration:
                        min: 20m
                        max: 45m
                anomalies: []
                """.formatted(sourceId, profile, sourceId);
    }
}
