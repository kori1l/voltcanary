package com.voltcanary.device.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public final class SourceRuntimeJson {

    private SourceRuntimeJson() {}

    public static Map<String, Object> objectMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(
                json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored mapping", failure);
        }
    }

    public static Map<String, String> stringMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(
                json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
            );
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored references", failure);
        }
    }
}
