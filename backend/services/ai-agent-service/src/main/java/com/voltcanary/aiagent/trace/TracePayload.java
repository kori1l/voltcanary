package com.voltcanary.aiagent.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.conversation.ConversationRedaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class TracePayload {
    public record Bounded(String json, int bytes, boolean truncated) { }
    private static final Pattern PRIVATE = Pattern.compile("(?i)reasoning|chain.?of.?thought|scratchpad|analysis|thoughts?|system.?prompt|raw.?body|headers?");
    private static final Pattern SECRET = Pattern.compile("(?i)password|secret|api.?key|token$|authorization|credential");
    private final ObjectMapper mapper;

    public Bounded encode(Map<String, Object> details, int maximum) {
        try {
            var safe = clean(details, 0);
            String json = mapper.writeValueAsString(safe);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            boolean truncated = json.contains("[truncated]");
            if (bytes.length > maximum) {
                json = mapper.writeValueAsString(Map.of("truncated", true, "safePayloadBytes", bytes.length,
                        "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        "fieldPaths", ((Map<?, ?>) safe).keySet().stream().limit(4).map(key -> {
                            String path = String.valueOf(key).replaceAll("[^A-Za-z0-9_.\\[\\]-]", "_");
                            return path.substring(0, Math.min(48, path.length()));
                        }).toList()));
                truncated = true;
            }
            return new Bounded(json, json.getBytes(StandardCharsets.UTF_8).length, truncated);
        } catch (Exception unsupported) {
            String marker = "{\"truncated\":true,\"captureCode\":\"UNSUPPORTED_PAYLOAD\"}";
            return new Bounded(marker, marker.getBytes(StandardCharsets.UTF_8).length, true);
        }
    }

    public Map<String, Object> decode(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception invalid) { return Map.of("captureCode", "UNAVAILABLE_PAYLOAD"); }
    }

    private Object clean(Object value, int depth) {
        if (depth > 8) return "[truncated]";
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.entrySet().stream().limit(80).forEach(entry -> {
                String key = ConversationRedaction.text(String.valueOf(entry.getKey()));
                if (PRIVATE.matcher(key).find()) return;
                if (key.length() > 120) key = key.substring(0, 120) + "[truncated]";
                result.put(key, SECRET.matcher(key).find() ? "[redacted]" : clean(entry.getValue(), depth + 1));
            });
            if (map.size() > 80) result.put("captureLimit", "[truncated]");
            return result;
        }
        if (value instanceof Collection<?> list) {
            var result = new java.util.ArrayList<>(list.stream().limit(40).map(item -> clean(item, depth + 1)).toList());
            if (list.size() > 40) result.add("[truncated]");
            return result;
        }
        if (value instanceof String text) {
            String safe = ConversationRedaction.text(text);
            return safe.length() > 2000 ? safe.substring(0, 2000) + "[truncated]" : safe;
        }
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof java.util.UUID || value instanceof Enum<?>) return value.toString();
        return clean(mapper.convertValue(value, Object.class), depth + 1);
    }
}
