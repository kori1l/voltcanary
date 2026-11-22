package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlCsvSchemaMapping;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MlImportSchema {
    private MlImportSchema() {}

    static MlLabelType parseLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MlLabelType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_LABEL");
        }
    }

    static void validateMetricUnit(String metricName, String unit) {
        if (unit == null || unit.isBlank()) {
            return;
        }
        String expected = expectedUnit(metricName);
        if (expected != null && !expected.equals(unit.trim())) {
            throw new IllegalArgumentException("METRIC_UNIT_MISMATCH");
        }
    }

    static String expectedUnit(String metricName) {
        String metric = metricName == null ? "" : metricName.trim();
        return switch (metric) {
            case "activePowerW", "active_power_w", "active_power" -> "W";
            case "voltage", "voltageV", "voltage_v" -> "V";
            case "current", "currentA", "current_a" -> "A";
            case "energyGrowth", "energy_growth", "energyGrowthWh", "energy_growth_wh" -> "Wh";
            case "temperature", "temperatureC", "temperature_c" -> "C";
            case "vibration", "vibrationMmS", "vibration_mm_s" -> "mm/s";
            case "runtime", "runtimeHours", "runtime_hours" -> "h";
            case "powerFactor", "power_factor" -> "ratio";
            case "frequency", "frequencyHz", "frequency_hz" -> "Hz";
            default -> null;
        };
    }

    static void validateMapping(MlCsvSchemaMapping mapping) {
        if (mapping == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "CSV schema mapping is required");
        }
        rejectCredentialLikeColumns(
                List.of(
                        mapping.timestampColumn(),
                        mapping.deviceKeyColumn(),
                        mapping.metricNameColumn(),
                        mapping.metricValueColumn()));
        if (mapping.metadataColumns() != null) {
            rejectCredentialLikeColumns(mapping.metadataColumns());
        }
        List<String> optionalColumns = new ArrayList<>();
        optionalColumns.add(mapping.existingDeviceIdColumn());
        optionalColumns.add(mapping.deviceTypeColumn());
        rejectCredentialLikeColumns(optionalColumns);
    }

    static void requireMappedColumns(Map<String, Integer> indexes, MlCsvSchemaMapping mapping) {
        for (String column :
                List.of(
                        mapping.timestampColumn(),
                        mapping.deviceKeyColumn(),
                        mapping.metricNameColumn(),
                        mapping.metricValueColumn())) {
            if (!indexes.containsKey(column)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "CSV column is missing: " + column);
            }
        }
    }

    static void rejectCredentialLikeColumns(List<String> columns) {
        for (String column : columns) {
            if (column == null) {
                continue;
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (normalized.contains("password")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("credential")
                    || normalized.contains("api_key")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "CSV mapping rejects credential-like fields");
            }
        }
    }

    static Map<String, Integer> indexes(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    static String cell(List<String> row, Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column);
        if (index == null || index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }

    static String field(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static void increment(Map<String, Long> values, String key) {
        values.merge(key, 1L, Long::sum);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
