package com.voltcanary.device.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.dto.DataSourceRequest;
import com.voltcanary.device.variable.application.VariableRetentionGuard;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SourceRuntimeMappings {
    private final ObjectMapper objectMapper;
    private final VariableRetentionGuard retention;

    public SourceRuntimeMappings(ObjectMapper objectMapper, VariableRetentionGuard retention) {
        this.objectMapper = objectMapper;
        this.retention = retention;
    }

    public void apply(DataSource source, DataSourceRequest request) {
        var stored = objectMap(source.getMappingJson());
        var requested = new java.util.LinkedHashMap<String, Object>(request.mapping() == null ? Map.of() : request.mapping());
        Object submitted = requested.remove("reviewedBooleanMappings");
        Object reviewed = stored.get("reviewedBooleanMappings");
        if (submitted != null && !submitted.equals(reviewed)) {
            throw new IllegalArgumentException("Reviewed mappings require confirmation");
        }
        retention.check(source.getId(), request.mapping());
        var policy = requested.containsKey("telemetryPolicy")
                ? objectMapper.convertValue(requested.get("telemetryPolicy"), com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.class)
                : null;
        var mappingConfig = objectMapper.valueToTree(requested);
        com.voltcanary.vcl.VclSourceBindingProfile.sampleHoldUs(mappingConfig);
        com.voltcanary.vcl.VclSourceBindingProfile.intervalReport(mappingConfig).ifPresent(contract -> {
            if (!retainedField(policy, contract.startKey(), "instant") || !retainedField(policy, contract.endKey(), "instant")) {
                throw new IllegalArgumentException("Interval bounds must be retained instant fields");
            }
        });
        com.voltcanary.vcl.VclSourceBindingProfile.occurrenceRouting(mappingConfig).ifPresent(contract -> {
            if (!retainedField(policy, contract.subjectKey(), "string") || !retainedField(policy, contract.occurrenceKey(), "string")) {
                throw new IllegalArgumentException("Occurrence routing requires retained string fields");
            }
        });
        rejectRawCredentials(requested, "mapping");
        rejectRawCredentials(request.adapterConfig(), "adapterConfig");
        if (reviewed instanceof Map<?, ?> conversions) {
            var oldPolicy = objectMapper.valueToTree(stored.get("telemetryPolicy"));
            var newPolicy = objectMapper.valueToTree(requested.get("telemetryPolicy"));
            for (var key : conversions.keySet()) {
                if (!java.util.Objects.equals(policyField(oldPolicy, key), policyField(newPolicy, key))) {
                    throw new IllegalArgumentException("Reviewed mapping field requires confirmation");
                }
            }
            requested.put("reviewedBooleanMappings", reviewed);
        }
        source.setMappingJson(toJson(requested));
        source.setAdapterConfigJson(toJson(request.adapterConfig()));
        source.setCredentialReferencesJson(toJson(request.credentialReferences()));
        source.setHomeRegion(normalizeHomeRegion(request.homeRegion()));
    }

    private static boolean retainedField(com.voltcanary.common.events.telemetry.TelemetryFieldPolicy policy, String key, String type) {
        return policy != null && policy.fields().stream()
                .anyMatch(field -> key.equals(field.key()) && field.store() && type.equals(field.type()));
    }

    private static com.fasterxml.jackson.databind.JsonNode policyField(com.fasterxml.jackson.databind.JsonNode policy, Object key) {
        for (var field : policy.path("fields")) if (key.equals(field.path("key").textValue())) return field;
        return null;
    }

    public Map<String, Object> objectMap(String json) { return SourceRuntimeJson.objectMap(objectMapper, json); }
    public Map<String, String> stringMap(String json) { return SourceRuntimeJson.stringMap(objectMapper, json); }
    private String normalizeHomeRegion(String homeRegion) {
        if (homeRegion == null || homeRegion.isBlank()) {
            return "default";
        }
        String normalized = homeRegion.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("homeRegion must be at most 64 characters");
        }
        return normalized;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid source runtime config JSON", exception);
        }
    }

    private void rejectRawCredentials(Map<String, Object> values, String field) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            String normalized = key == null ? "" : key.toLowerCase();
            if (normalized.contains("password")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("credential")) {
                throw new IllegalArgumentException(field + " must not contain raw credentials");
            }
            if (value instanceof Map<?, ?> nested) {
                rejectRawCredentials(castObjectMap(nested), field);
            }
        });
    }

    private Map<String, Object> castObjectMap(Map<?, ?> nested) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        nested.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

}
