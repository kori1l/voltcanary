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
            } else if ("decimal".equals(field.get("type"))) {
                try {
                    if (!canonicalDecimal(value).equals(value) || "boolean".equals(field.get("unit"))) return false;
                } catch (IllegalArgumentException invalid) { return false; }
            } else if ("point".equals(field.get("type"))) {
                try { canonicalPoint(value); }
                catch (IllegalArgumentException invalid) { return false; }
                if (!"1".equals(field.get("unit"))) return false;
            } else if ("spatial_ref".equals(field.get("type"))) {
                try { canonicalSpatialRef(value); }
                catch (IllegalArgumentException invalid) { return false; }
                if (!"1".equals(field.get("unit"))) return false;
            } else if ("instant".equals(field.get("type"))) {
                try { canonicalInstant(value); }
                catch (IllegalArgumentException invalid) { return false; }
                if (!"1".equals(field.get("unit"))) return false;
            } else if ("string".equals(field.get("type"))) {
                if (!text(value, 512)) return false;
            } else if (!"boolean".equals(field.get("type")) || !(value instanceof Boolean)) return false;
        }
        return true;
    }

    /** Decimal text is an explicit lossless source contract, independent of legacy JSON numbers. */
    public static String canonicalDecimal(Object value) {
        if (!(value instanceof String text) || text.length() > 52
                || !text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,12})?")) {
            throw new IllegalArgumentException("Invalid exact decimal");
        }
        var number = new java.math.BigDecimal(text);
        if (number.precision() > 38) throw new IllegalArgumentException("Invalid exact decimal");
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    public static Map<String, Double> canonicalPoint(Object value) {
        if (!(value instanceof Map<?, ?> point) || !point.keySet().equals(java.util.Set.of("longitude", "latitude"))) {
            throw new IllegalArgumentException("Invalid WGS84 point");
        }
        return Map.of("longitude", coordinate(point.get("longitude"), 180), "latitude", coordinate(point.get("latitude"), 90));
    }

    private static double coordinate(Object value, int bound) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || Math.abs(number.doubleValue()) > bound) throw new IllegalArgumentException("Invalid WGS84 coordinate");
        // Reject an out-of-range exact decimal even when conversion rounds it to the boundary.
        if (number instanceof java.math.BigDecimal decimal
                && decimal.abs().compareTo(java.math.BigDecimal.valueOf(bound)) > 0) {
            throw new IllegalArgumentException("Invalid WGS84 coordinate");
        }
        return number.doubleValue() == 0 ? 0 : number.doubleValue();
    }

    public static String canonicalSpatialRef(Object value) {
        if (!(value instanceof String text) || text.length() != 36) throw new IllegalArgumentException("Invalid spatial ref");
        var uuid = java.util.UUID.fromString(text);
        if (!uuid.toString().equals(text)) throw new IllegalArgumentException("Invalid spatial ref");
        return text;
    }

    public static String canonicalInstant(Object value) {
        if (!(value instanceof String text) || text.length() > 27 || !text.endsWith("Z")) {
            throw new IllegalArgumentException("Invalid UTC instant");
        }
        try {
            var instant = java.time.Instant.parse(text);
            int year = instant.atOffset(java.time.ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999 || instant.getNano() % 1000 != 0 || !instant.toString().equals(text)) {
                throw new IllegalArgumentException("Invalid UTC instant");
            }
            return text;
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid UTC instant", invalid);
        }
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
