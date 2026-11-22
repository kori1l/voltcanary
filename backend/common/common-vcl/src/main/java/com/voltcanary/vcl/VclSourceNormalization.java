package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared source normalization for live ingestion and exact-revision replay. */
public final class VclSourceNormalization {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VclSourceNormalization() {}

    public static Map<String, Object> normalize(
        Map<String, Object> input,
        Map<String, Object> mapping
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (var entry : input.entrySet())
            metrics.put(entry.getKey().trim(), normalizeValue(entry.getValue()));
        if (mapping != null && mapping.containsKey("telemetryPolicy")) {
            try {
                var policy = MAPPER.convertValue(
                    mapping.get("telemetryPolicy"),
                    com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.class
                );
                var conversions = reviewedMappings(mapping.get("reviewedBooleanMappings"), policy);
                metrics = new LinkedHashMap<>(
                    policy.select(metrics, (field, value) -> {
                        var tokens = conversions.get(field.key());
                        if (tokens == null) return value;
                        if (!(value instanceof String text)) throw new IllegalArgumentException(
                            "Reviewed mapping requires text"
                        );
                        return tokens.evaluate(text, "");
                    })
                );
            } catch (IllegalArgumentException invalid) {
                throw new VclException("SOURCE_NORMALIZATION_INVALID", "");
            }
        }
        return Map.copyOf(metrics);
    }

    private static Map<String, VclBooleanTokens> reviewedMappings(
        Object value,
        com.voltcanary.common.events.telemetry.TelemetryFieldPolicy policy
    ) {
        if (value == null) return Map.of();
        var json = MAPPER.valueToTree(value);
        if (!json.isObject() || json.size() > 32) throw new IllegalArgumentException(
            "Invalid reviewed mapping configuration"
        );
        var result = new LinkedHashMap<String, VclBooleanTokens>();
        json.fields().forEachRemaining(entry -> {
            if (
                policy
                    .fields()
                    .stream()
                    .noneMatch(
                        field ->
                            field.store() &&
                            "boolean".equals(field.type()) &&
                            field.key().equals(entry.getKey())
                    )
            ) throw new IllegalArgumentException("Invalid reviewed mapping field");
            result.put(entry.getKey(), VclBooleanTokens.parse(entry.getValue(), ""));
        });
        return result;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value;
    }
}
