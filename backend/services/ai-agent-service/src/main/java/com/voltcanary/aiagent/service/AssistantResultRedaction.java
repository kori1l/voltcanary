package com.voltcanary.aiagent.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class AssistantResultRedaction {

    private static final Pattern SECRET = Pattern.compile(
        "password|secret|api.?key|authorization|credential|token$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOKEN = Pattern.compile(
        "(?i)bearer\\s+\\S+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|\\bsk-[A-Za-z0-9_-]{16,}"
    );

    private AssistantResultRedaction() {}

    static Map<String, Object> result(Map<String, Object> value) {
        return map(value, 0);
    }

    private static Map<String, Object> map(Map<?, ?> value, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        value
            .entrySet()
            .stream()
            .limit(100)
            .forEach(entry -> {
                String key = String.valueOf(entry.getKey());
                result.put(
                    key,
                    SECRET.matcher(key).find() ? "[redacted]" : item(entry.getValue(), depth + 1)
                );
            });
        return result;
    }

    private static Object item(Object value, int depth) {
        if (depth > 8) return "[truncated]";
        if (value instanceof Map<?, ?> object) return map(object, depth);
        if (value instanceof Collection<?> array) return array
            .stream()
            .limit(100)
            .map(child -> item(child, depth + 1))
            .toList();
        if (value instanceof String text) return TOKEN.matcher(text).find()
            ? "[redacted]"
            : text.substring(0, Math.min(text.length(), 8000));
        return value;
    }
}
