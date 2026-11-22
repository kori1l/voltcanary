package com.voltcanary.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider output constraint; ordinary authorization/compiler checks remain mandatory. */
public final class DeviceCreationSchema {

    private DeviceCreationSchema() {}

    public static Map<String, Object> response(Map<String, Object> context) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("name", nullable(string()));
        fields.put("type", choice(context, "deviceTypes", "code"));
        fields.put("deviceTypeRef", choice(context, "deviceTypes", "ref"));
        fields.put("locationRef", choice(context, "locations", "ref"));
        fields.put("manufacturer", nullable(string()));
        fields.put("model", nullable(string()));
        var suggestions = new LinkedHashMap<String, Object>();
        for (String key : List.of("name", "type", "deviceTypeRef", "locationRef"))
            suggestions.put(key, nullable(string()));
        var question = object(
            Map.of(
                "missingFields",
                Map.of(
                    "type",
                    "array",
                    "maxItems",
                    3,
                    "items",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("name", "type", "deviceTypeRef", "locationRef")
                    )
                ),
                "suggestedReply",
                string(),
                "suggestionLabel",
                string(),
                "suggestedValues",
                object(suggestions)
            )
        );
        var action = object(
            Map.of(
                "tool",
                Map.of("type", "string", "enum", List.of("CREATE_DEVICE")),
                "title",
                string(),
                "description",
                string(),
                "parameters",
                object(fields)
            )
        );
        return object(
            Map.of(
                "summary",
                string(),
                "explanation",
                string(),
                "clarification",
                nullable(question),
                "actions",
                Map.of("type", "array", "maxItems", 1, "items", action)
            )
        );
    }

    private static Map<String, Object> choice(
        Map<String, Object> context,
        String catalogue,
        String key
    ) {
        var choices = new ArrayList<String>();
        if (context.get(catalogue) instanceof List<?> entries) for (Object entry : entries) {
            if (
                entry instanceof Map<?, ?> value && value.get(key) instanceof String text
            ) choices.add(text);
        }
        return choices.isEmpty()
            ? Map.of("type", "null")
            : nullable(Map.of("type", "string", "enum", choices));
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of(
            "type",
            "object",
            "properties",
            fields,
            "required",
            new ArrayList<>(fields.keySet()),
            "additionalProperties",
            false
        );
    }
}
