package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.model.AgentAction;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ToolExecutor {

    private final RestClient deviceClient;
    private final RestClient alertClient;
    private final RestClient simulatorClient;

    public ToolExecutor(RestClient.Builder restClientBuilder, AiAgentProperties properties) {
        this.deviceClient = restClientBuilder.baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.alertClient = restClientBuilder.baseUrl(properties.getServices().getAlertBaseUrl()).build();
        this.simulatorClient = restClientBuilder.baseUrl(properties.getServices().getSimulatorBaseUrl()).build();
    }

    public ActionExecutionResponse execute(AgentAction action, String authorization, UUID userId, Map<String, Object> outputs) {
        try {
            Map<String, Object> result = switch (action.tool()) {
                case CREATE_DEVICE -> createDevice(action, authorization);
                case CREATE_DATA_SOURCE -> createDataSource(action, authorization, outputs);
                case CREATE_SIMULATOR_CONFIG -> createSimulatorConfig(action, authorization, userId, outputs);
                case VALIDATE_SIMULATOR_YAML -> validateSimulatorYaml(action, outputs);
                case CREATE_ALERT_RULE -> createAlertRule(action, authorization);
                case UPDATE_ALERT_RULE -> updateAlertRule(action, authorization);
                case TEST_ALERT_RULE -> testAlertRule(action, authorization);
                case EXPLAIN_ALERT -> explainAlert(action, authorization);
                case OPEN_TELEMETRY_WINDOW -> openTelemetryWindow(action);
                case CREATE_DEMO_DEVICE -> createDemoDevice(action, authorization, userId);
                case START_SIMULATION -> startSimulation(action, authorization, userId, outputs);
                case STOP_SIMULATION -> stopSimulation(action, authorization, outputs);
            };
            outputs.putAll(result);
            return new ActionExecutionResponse(action.id(), action.tool().name(), "SUCCESS", result, null);
        } catch (Exception exception) {
            return new ActionExecutionResponse(action.id(), action.tool().name(), "FAILED", Map.of(), exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDevice(AgentAction action, String authorization) {
        Map<String, Object> response = postDevice(deviceBody(action.parameters()), authorization);
        return resultWith("deviceId", response.get("id"), "device", response);
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
    private Map<String, Object> validateSimulatorYaml(AgentAction action, Map<String, Object> outputs) {
        Object deviceId = firstNonNull(action.parameters().get("deviceId"), outputs.get("deviceId"), new UUID(0, 0));
        Map<String, Object> response = simulatorClient.post()
                .uri("/simulation/devices/{deviceId}/config/validate", deviceId)
                .body(Map.of("yamlConfig", action.parameters().getOrDefault("yamlConfig", "")))
                .retrieve()
                .body(Map.class);
        return Map.of("validation", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAlertRule(AgentAction action, String authorization) {
        Map<String, Object> response = alertClient.post()
                .uri("/alert-rules")
                .header("Authorization", authorization)
                .body(alertRuleBody(action.parameters()))
                .retrieve()
                .body(Map.class);
        return resultWith("ruleId", response.get("id"), "rule", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateAlertRule(AgentAction action, String authorization) {
        Object ruleId = required(action.parameters().get("ruleId"), "ruleId is required to update an alert rule");
        Map<String, Object> response = alertClient.put()
                .uri("/alert-rules/{ruleId}", ruleId)
                .header("Authorization", authorization)
                .body(alertRuleBody(action.parameters()))
                .retrieve()
                .body(Map.class);
        return resultWith("ruleId", response.get("id"), "rule", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> testAlertRule(AgentAction action, String authorization) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "deviceId", action.parameters().get("deviceId"));
        putIfPresent(body, "from", action.parameters().get("from"));
        putIfPresent(body, "to", action.parameters().get("to"));
        body.put("rule", alertRuleBody(action.parameters()));
        Map<String, Object> response = alertClient.post()
                .uri("/alert-rules/test")
                .header("Authorization", authorization)
                .body(body)
                .retrieve()
                .body(Map.class);
        return Map.of("ruleTest", response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explainAlert(AgentAction action, String authorization) {
        Object alertId = required(action.parameters().get("alertId"), "alertId is required to explain an alert");
        Map<String, Object> alert = alertClient.get()
                .uri("/alerts/{alertId}", alertId)
                .header("Authorization", authorization)
                .retrieve()
                .body(Map.class);

        Map<String, Object> rule = Map.of();
        Object ruleId = alert.get("ruleId");
        if (ruleId != null && isUuid(ruleId.toString())) {
            rule = alertClient.get()
                    .uri("/alert-rules/{ruleId}", ruleId)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(Map.class);
        }

        String metric = rule.isEmpty() ? "activePowerW" : stringValue(rule.get("metric"), "activePowerW");
        Map<String, Object> routeParams = new LinkedHashMap<>();
        routeParams.put("deviceId", alert.get("deviceId"));
        routeParams.put("metric", metric);
        routeParams.put("from", alert.get("calculationWindowStart"));
        routeParams.put("to", alert.get("calculationWindowEnd"));
        routeParams.put("alertId", alertId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", explanationSummary(alert, rule));
        result.put("alert", alert);
        result.put("rule", rule);
        result.put("route", telemetryRoute(routeParams));
        return result;
    }

    private Map<String, Object> openTelemetryWindow(AgentAction action) {
        Object deviceId = required(action.parameters().get("deviceId"), "deviceId is required to open telemetry");
        Map<String, Object> parameters = new LinkedHashMap<>(action.parameters());
        parameters.put("deviceId", deviceId);
        return Map.of("route", telemetryRoute(parameters));
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
        simulatorParameters.put("yamlConfig", template(stringValue(action.parameters().get("profile"), "FRIDGE"), sourceId));
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
    private String template(String profile, String sourceId) {
        Map<String, Object> response = simulatorClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/simulation/templates/{profile}")
                        .queryParam("sourceId", sourceId)
                        .build(profile))
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

    private Map<String, Object> alertRuleBody(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String key : new String[] {
                "name", "description", "ruleType", "enabled", "scopeType", "scopeId", "deviceType", "metric",
                "conditionType", "threshold", "thresholdUnit", "baselineMultiplier", "minPoints", "severity",
                "cooldownSeconds", "deduplicationWindowSeconds", "notificationChannelIds", "config"
        }) {
            putIfPresent(body, key, parameters.get(key));
        }
        return body;
    }

    private String telemetryRoute(Map<String, Object> parameters) {
        Object deviceId = required(parameters.get("deviceId"), "deviceId is required to build telemetry route");
        StringBuilder route = new StringBuilder("/devices/")
                .append(encode(deviceId))
                .append("?tab=telemetry");
        appendQuery(route, "metric", parameters.get("metric"));
        appendQuery(route, "from", parameters.get("from"));
        appendQuery(route, "to", parameters.get("to"));
        appendQuery(route, "alertId", parameters.get("alertId"));
        return route.toString();
    }

    private String explanationSummary(Map<String, Object> alert, Map<String, Object> rule) {
        String title = stringValue(alert.get("title"), "Alert");
        String severity = stringValue(alert.get("severity"), "UNKNOWN");
        String status = stringValue(alert.get("status"), "UNKNOWN");
        String ruleName = rule.isEmpty() ? stringValue(alert.get("ruleId"), "built-in rule") : stringValue(rule.get("name"), "configured rule");
        return title + " is a " + severity + " alert in " + status + " status. It was produced by " + ruleName + ".";
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

    private void appendQuery(StringBuilder route, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            route.append('&').append(key).append('=').append(encode(value));
        }
    }

    private Object required(Object value, String message) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String encode(Object value) {
        return URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
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
