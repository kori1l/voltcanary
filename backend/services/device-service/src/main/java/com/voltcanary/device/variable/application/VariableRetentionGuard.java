package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryFieldPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class VariableRetentionGuard {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public void check(UUID sourceId, Map<String, Object> mapping) {
        if (sourceId == null) return;
        jdbc.queryForList("select id from data_sources where id=? for update", sourceId);
        List<String> dependencies = jdbc.query(
            """
            select input_paths_json from source_variable_bindings
            where data_source_id=? and enabled=true
            """,
            (row, index) -> row.getString(1),
            sourceId
        );
        if (dependencies.isEmpty()) return;
        TelemetryFieldPolicy policy = policy(mapping);
        String currentJson = jdbc.queryForObject(
            "select mapping_json from data_sources where id=?",
            String.class,
            sourceId
        );
        TelemetryFieldPolicy current = policy(
            com.voltcanary.device.telemetry.SourceRuntimeJson.objectMap(mapper, currentJson)
        );
        for (String json : dependencies) {
            for (String path : paths(json).values()) {
                if (!retained(policy, path)) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Input " + path + " is required by an enabled variable"
                    );
                }
                if (current != null) {
                    var before = current
                        .fields()
                        .stream()
                        .filter(field -> field.path().equals(path))
                        .findFirst()
                        .orElseThrow();
                    var after =
                        policy == null
                            ? null
                            : policy
                                  .fields()
                                  .stream()
                                  .filter(field -> field.path().equals(path))
                                  .findFirst()
                                  .orElse(null);
                    if (
                        after == null ||
                        !java.util.Objects.equals(before.key(), after.key()) ||
                        !java.util.Objects.equals(before.unit(), after.unit())
                    ) {
                        throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Disable dependent variables before changing input identity or unit"
                        );
                    }
                }
            }
        }
    }

    public TelemetryFieldPolicy policy(Map<String, Object> mapping) {
        if (mapping == null || !mapping.containsKey("telemetryPolicy")) return null;
        return mapper.convertValue(mapping.get("telemetryPolicy"), TelemetryFieldPolicy.class);
    }

    public boolean retained(TelemetryFieldPolicy policy, String path) {
        if (policy == null) {
            return List.of(
                "temperatureC",
                "voltageV",
                "currentA",
                "activePowerW",
                "energyWh",
                "rssi",
                "vibrationMmS",
                "runtimeHours",
                "powerFactor",
                "frequencyHz",
                "latitude",
                "longitude",
                "speedKmh",
                "headingDeg",
                "fuelPercent",
                "odometerKm",
                "ignition"
            ).contains(path);
        }
        return policy
            .fields()
            .stream()
            .anyMatch(
                field -> field.path().equals(path) && field.store() && "number".equals(field.type())
            );
    }

    private Map<String, String> paths(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid stored variable paths", failure);
        }
    }
}
