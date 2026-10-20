package com.voltcanary.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.ConfiguredFields;
import com.voltcanary.telemetry.repository.TelemetryReadingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TelemetryFieldReads {
    public record Field(String key, String label, String type, String unit, Object value, boolean display, String sourceId, Instant recordedAt) { }
    private final TelemetryReadingRepository readings;
    private final ObjectMapper mapper;
    public TelemetryFieldReads(TelemetryReadingRepository readings, ObjectMapper mapper) {
        this.readings = readings;
        this.mapper = mapper;
    }
    public List<Field> latest(UUID device, String sourceId) {
        List<Field> result = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (var reading : readings.findTop100ByDeviceIdOrderByRecordedAtDesc(device)) {
            if (sourceId != null && !sourceId.equals(reading.getSourceId()) || !seen.add(reading.getSourceId())) continue;
            Map<String,Object> metrics;
            try { metrics = mapper.readValue(reading.getMetricsJson(), new TypeReference<Map<String,Object>>() { }); }
            catch (Exception failure) { throw new IllegalStateException("Invalid stored telemetry", failure); }
            var fields = ConfiguredFields.values(metrics);
            if (!fields.isEmpty()) {
                fields.forEach((key, field) -> result.add(new Field(key, (String) field.get("label"), (String) field.get("type"),
                        (String) field.get("unit"), field.get("value"), Boolean.TRUE.equals(field.get("display")), reading.getSourceId(), reading.getRecordedAt())));
            } else {
                metrics.forEach((key,value) -> {
                    if (value instanceof Map<?,?> context && key.equals("transport")) {
                        context.forEach((name,item) -> addLegacy(result, "transport." + name, item, reading.getSourceId(), reading.getRecordedAt()));
                    } else if (!key.equals(ConfiguredFields.KEY)) addLegacy(result, key, value, reading.getSourceId(), reading.getRecordedAt());
                });
            }
        }
        return result;
    }
    private void addLegacy(List<Field> result, String key, Object value, String source, Instant timestamp) {
        if (value == null || !(value instanceof Number || value instanceof String || value instanceof Boolean)) return;
        result.add(new Field(key, key, value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "string", "", value, true, source, timestamp));
    }
}
