package com.voltcanary.aiagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConversationRedaction {

    private static final Pattern TOKEN = Pattern.compile(
        "(?i)bearer\\s+[^\\s,;]+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|\\bsk-[A-Za-z0-9_-]{8,}|\\bAIza[A-Za-z0-9_-]{30,}|\\bAQ\\.[A-Za-z0-9_-]{30,}|\\b(?:ghp_|github_pat_)[A-Za-z0-9_]{16,}|https?://[^\\s/@]+:[^\\s/@]+@"
    );
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)((?:password|secret|api[_ -]?key|(?:access[_ -]?|refresh[_ -]?|source[_ -]?|api[_ -]?)?token|authorization|credential)\\s*[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)"
    );
    private static final Pattern SECRET_KEY = Pattern.compile(
        "(?i)password|secret|api.?key|token|authorization|credential"
    );

    private ConversationRedaction() {}

    public static String text(String text) {
        if (text == null) return null;
        return ASSIGNMENT.matcher(TOKEN.matcher(text).replaceAll("[redacted]")).replaceAll(
            "$1[redacted]"
        );
    }

    public static boolean marked(String original, String safe) {
        return (
            !java.util.Objects.equals(original, safe) ||
            (safe != null && safe.contains("[redacted]"))
        );
    }

    public static <T> T value(T value, Class<T> type, ObjectMapper mapper) {
        Map<String, Object> fields = mapper.convertValue(value, new TypeReference<>() {});
        return mapper.convertValue(clean(fields), type);
    }

    private static Object clean(Object value) {
        if (value instanceof String string) return text(string);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) ->
                result.put(
                    String.valueOf(key),
                    SECRET_KEY.matcher(String.valueOf(key)).find() ? "[redacted]" : clean(item)
                )
            );
            return result;
        }
        if (value instanceof List<?> list) return list
            .stream()
            .map(ConversationRedaction::clean)
            .toList();
        return value;
    }
}
