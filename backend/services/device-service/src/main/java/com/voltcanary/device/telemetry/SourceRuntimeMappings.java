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
        retention.check(source.getId(), request.mapping());
        if (request.mapping() != null && request.mapping().containsKey("telemetryPolicy")) {
            objectMapper.convertValue(request.mapping().get("telemetryPolicy"), com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.class);
        }
        rejectRawCredentials(request.mapping(), "mapping");
        rejectRawCredentials(request.adapterConfig(), "adapterConfig");
        source.setMappingJson(toJson(request.mapping()));
        source.setAdapterConfigJson(toJson(request.adapterConfig()));
        source.setCredentialReferencesJson(toJson(request.credentialReferences()));
        source.setHomeRegion(normalizeHomeRegion(request.homeRegion()));
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
