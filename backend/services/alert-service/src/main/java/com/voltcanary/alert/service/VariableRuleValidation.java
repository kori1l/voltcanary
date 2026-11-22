package com.voltcanary.alert.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertInternalProperties;
import com.voltcanary.alert.config.VariableRuleProperties;
import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.common.events.telemetry.VariableRuleSelector;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class VariableRuleValidation {

    private final ObjectMapper mapper;
    private final AlertAccessService access;
    private final RestClient telemetry;
    private final String token;

    public VariableRuleValidation(
        ObjectMapper mapper,
        AlertAccessService access,
        VariableRuleProperties properties,
        AlertInternalProperties internal,
        RestClient.Builder builder
    ) {
        this.mapper = mapper;
        this.access = access;
        token = internal.getServiceToken();
        var factory = new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        );
        factory.setReadTimeout(Duration.ofSeconds(3));
        telemetry = builder
            .baseUrl(properties.getTelemetryServiceBaseUrl())
            .requestFactory(factory)
            .build();
    }

    public boolean applies(String metric, Map<String, Object> config) {
        return (
            (metric != null && metric.startsWith("var_")) ||
            (config != null && config.containsKey("variable"))
        );
    }

    public void validate(AlertRule rule, String userId, Integer expectedVersion) {
        Map<String, Object> config = config(rule);
        if (!applies(rule.getMetric(), config)) return;
        var selector = mapper.convertValue(config.get("variable"), VariableRuleSelector.class);
        if (
            selector == null ||
            selector.variableId() == null ||
            selector.dataSourceId() == null ||
            selector.unit() == null ||
            !("var_" + selector.variableId().toString().replace("-", "")).equals(
                rule.getMetric()
            ) ||
            !"DEVICE".equals(rule.getScopeType()) ||
            rule.getScopeId() == null ||
            !Set.of("ABOVE_THRESHOLD", "BELOW_THRESHOLD").contains(rule.getConditionType()) ||
            selector.maxLiveAgeSeconds() < 1 ||
            selector.maxLiveAgeSeconds() > 86400 ||
            !selector
                .unit()
                .equals(rule.getThresholdUnit() == null ? "" : rule.getThresholdUnit()) ||
            rule.getThreshold() == null ||
            !Double.isFinite(rule.getThreshold().doubleValue())
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid source-bound variable rule"
            );
        }
        if (expectedVersion != null) {
            Object value = config.get("expectedVersion");
            if (!(value instanceof Number number) || number.intValue() != expectedVersion) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Variable rule changed; reload"
                );
            }
        }
        UUID account = access.ownAccount(userId);
        var device = access.resolveDeviceRuleContext(rule.getScopeId(), null);
        if (
            !account.equals(rule.getAccountId()) ||
            device == null ||
            !account.equals(device.accountId())
        ) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Owned variable resource required"
            );
        }
        try {
            JsonNode descriptors = telemetry
                .get()
                .uri("/internal/telemetry/devices/{id}/variables", rule.getScopeId())
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(JsonNode.class);
            if (descriptors != null && descriptors.isArray()) {
                for (JsonNode descriptor : descriptors) {
                    if (
                        selector
                            .variableId()
                            .toString()
                            .equals(descriptor.path("variableId").asText()) &&
                        selector
                            .dataSourceId()
                            .toString()
                            .equals(descriptor.path("dataSourceId").asText()) &&
                        selector.unit().equals(descriptor.path("unit").asText()) &&
                        (!rule.isEnabled() || descriptor.path("enabled").asBoolean())
                    ) return;
                }
            }
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Variable source/unit is unavailable"
            );
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Variable metadata unavailable"
            );
        }
    }

    public Map<String, Object> config(AlertRule rule) {
        try {
            return mapper.readValue(
                rule.getConfigJson() == null ? "{}" : rule.getConfigJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid rule configuration", failure);
        }
    }
}
