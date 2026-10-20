package com.voltcanary.common.events.telemetry;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declarative paths are relative to canonical metrics, never executable expressions. */
public record TelemetryFieldPolicy(List<Field> fields) {
    private static final Set<String> CANONICAL = Set.of("latitude", "longitude", "speedKmh", "headingDeg", "ignition", "fuelPercent", "odometerKm", "activePowerW", "voltageV", "currentA", "energyWh", "rssi", "temperatureC", "vibrationMmS", "runtimeHours", "powerFactor", "frequencyHz");

    public record Field(String path, String key, String label, String type, String unit, boolean store, boolean display) { }

    public TelemetryFieldPolicy {
        if (fields == null || fields.size() > 32) throw new IllegalArgumentException("Use at most 32 fields");
        fields = List.copyOf(fields);
        Set<String> keys = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (Field field : fields) {
            if (field == null || field.key() == null || !field.key().matches("[A-Za-z][A-Za-z0-9_.]{0,63}")
                    || !keys.add(field.key()) || !paths.add(field.path()) || field.path() == null
                    || !field.path().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,3}")
                    || !ConfiguredFields.text(field.label(), 80) || field.label().isBlank()
                    || !ConfiguredFields.text(field.unit(), 24) || field.type() == null || !Set.of("number", "string", "boolean").contains(field.type())
                    || field.key().equals(ConfiguredFields.KEY) || field.key().equals("transport")
                    || field.key().startsWith("var_") || field.key().equals("processingContext")) {
                throw new IllegalArgumentException("Invalid field path, key, label or type");
            }
            if (field.type().equals("number") && field.unit().equals("boolean")) throw new IllegalArgumentException("Boolean is a reserved state-series unit");
            if (CANONICAL.contains(field.key()) && (!field.path().equals(field.key()) || !field.type().equals("number"))) {
                throw new IllegalArgumentException("Canonical metric keys require their canonical path and numeric type");
            }
        }
        boolean latitude = fields.stream().anyMatch(field -> field.store() && field.key().equals("latitude"));
        boolean longitude = fields.stream().anyMatch(field -> field.store() && field.key().equals("longitude"));
        if (latitude != longitude) throw new IllegalArgumentException("Store latitude and longitude together");
    }

    public Map<String, Object> select(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> configured = new LinkedHashMap<>();
        Map<String, Object> transport = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!field.store()) continue;
            Object value = input;
            for (String segment : field.path().split("\\.")) value = value instanceof Map<?, ?> map ? map.get(segment) : null;
            if (value == null) continue;
            Map<String, Object> descriptor = Map.of("value", value, "type", field.type(), "label", field.label(), "unit", field.unit(), "display", field.display());
            if (!ConfiguredFields.valid(Map.of(ConfiguredFields.KEY, Map.of(field.key(), descriptor)))) {
                throw new IllegalArgumentException("Invalid value for " + field.key());
            }
            configured.put(field.key(), descriptor);
            if (CANONICAL.contains(field.key())) result.put(field.key(), value);
            if (field.path().startsWith("transport.") && field.path().split("\\.").length == 2) {
                transport.put(field.path().substring(10), value);
            }
        }
        if (result.containsKey("latitude") && result.containsKey("longitude") && !transport.isEmpty()) result.put("transport", transport);
        if (!configured.isEmpty()) result.put(ConfiguredFields.KEY, configured);
        return result;
    }
}
