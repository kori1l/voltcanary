package com.voltcanary.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Allows assertions on serialized field names without weakening production contracts. */
final class InferenceTestContext {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InferenceTestContext() {}

    static Map<String, Object> asMap(Object value) {
        return JSON.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }
}
