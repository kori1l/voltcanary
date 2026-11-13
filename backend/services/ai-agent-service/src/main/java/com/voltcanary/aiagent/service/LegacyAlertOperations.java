package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentAction;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/** Existing alert operations and telemetry links, isolated from new reviewed owner workflows. */
final class LegacyAlertOperations {
    private final RestClient alertClient;
    LegacyAlertOperations(RestClient alertClient) { this.alertClient = alertClient; }

    @SuppressWarnings("unchecked")
    Map<String, Object> createAlertRule(AgentAction action, String authorization) {
        Map<String, Object> response = alertClient.post()
                .uri("/alert-rules")
                .header("Authorization", authorization)
                .body(alertRuleBody(action.parameters()))
                .retrieve()
                .body(Map.class);
        return resultWith("ruleId", response.get("id"), "rule", response);
    }


    @SuppressWarnings("unchecked")
    Map<String, Object> updateAlertRule(AgentAction action, String authorization) {
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
    Map<String, Object> testAlertRule(AgentAction action, String authorization) {
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
    Map<String, Object> explainAlert(AgentAction action, String authorization) {
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


    Map<String, Object> openTelemetryWindow(AgentAction action) {
        Object deviceId = required(action.parameters().get("deviceId"), "deviceId is required to open telemetry");
        Map<String, Object> parameters = new LinkedHashMap<>(action.parameters());
        parameters.put("deviceId", deviceId);
        return Map.of("route", telemetryRoute(parameters));
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


    private void appendQuery(StringBuilder route, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            route.append('&').append(key).append('=').append(encode(value));
        }
    }


    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }


    private String encode(Object value) {
        return URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
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


    private String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

}
