package com.voltcanary.common.events.telemetry;

import java.util.Map;

/** Bounded typed values selected by an authenticated source policy. */
public final class ConfiguredFields {
    public static final String KEY = "configuredFields";
    private ConfiguredFields() { }

    public static boolean valid(Map<String, Object> metrics) {
        if (metrics == null || !metrics.containsKey(KEY)) return true;
        if (!(metrics.get(KEY) instanceof Map<?, ?> fields) || fields.size() > 32) return false;
        for (var entry : fields.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z][A-Za-z0-9_.]{0,63}")) return false;
            if (!(entry.getValue() instanceof Map<?, ?> field) || field.size() != 5
                    || !field.keySet().equals(java.util.Set.of("value", "type", "label", "unit", "display"))) return false;
            if (!text(field.get("label"), 80) || !text(field.get("unit"), 24) || !(field.get("display") instanceof Boolean)) return false;
            Object value = field.get("value");
            if ("number".equals(field.get("type"))) {
                if ("boolean".equals(field.get("unit"))) return false;
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || Math.abs(number.doubleValue()) > 1e12) return false;
            } else if ("string".equals(field.get("type"))) {
                if (!text(value, 512)) return false;
            } else if (!"boolean".equals(field.get("type")) || !(value instanceof Boolean)) return false;
        }
        return true;
    }

    public static boolean text(Object value, int max) {
        return value instanceof String text && text.length() <= max && text.chars().noneMatch(Character::isISOControl);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> values(Map<String, Object> metrics) {
        if (!valid(metrics)) throw new IllegalArgumentException("Invalid configured telemetry fields");
        Object value = metrics == null ? null : metrics.get(KEY);
        return value == null ? Map.of() : (Map<String, Map<String, Object>>) value;
    }
}
