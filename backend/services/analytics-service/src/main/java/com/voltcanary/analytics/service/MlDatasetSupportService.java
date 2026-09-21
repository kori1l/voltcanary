package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlCandidateWindowKind;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MlDatasetSupportService {

    public static final int DEFAULT_MIN_SAMPLES_PER_METRIC = 24;
    public static final long DEFAULT_MAX_SAMPLING_GAP_SECONDS = 6 * 60 * 60;
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    public static final String PREVIEW_ALGORITHM_VERSION = "ml-dataset-workbench-preview-v1";
    public static final String DEFAULT_SPLIT_CONFIG = "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}";

    private final ObjectMapper objectMapper;

    public MlDatasetSupportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MlMetricSelectionMode normalizedMetricSelectionMode(MlMetricSelectionMode value) {
        return value == null ? MlMetricSelectionMode.COMMON : value;
    }

    public MlModelFamily normalizedModelFamily(MlModelFamily value) {
        return value == null ? MlModelFamily.ANOMALY_DETECTION : value;
    }

    public MlTrainingSplitStrategy normalizedSplitStrategy(MlTrainingSplitStrategy value) {
        return value == null ? MlTrainingSplitStrategy.TIME_BASED : value;
    }

    public List<String> normalizedMetrics(List<String> metrics) {
        if (metrics == null) {
            return List.of();
        }
        return metrics.stream()
                .filter(metric -> metric != null && !metric.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public List<UUID> normalizedDeviceIds(List<UUID> deviceIds) {
        if (deviceIds == null) {
            return List.of();
        }
        return deviceIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    public int minSamplesPerMetric(Integer value) {
        return value == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : value;
    }

    public long maxSamplingGapSeconds(Long value) {
        return value == null ? DEFAULT_MAX_SAMPLING_GAP_SECONDS : value;
    }

    public String featureConfigVersion(String value) {
        return blankToDefault(value, "feature-config-v1");
    }

    public int candidateLimit(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(8, value));
    }

    public String previewId(String previewHash) {
        if (previewHash == null || previewHash.length() <= "sha256:".length()) {
            return "prev_unknown";
        }
        return "prev_" + previewHash.substring("sha256:".length(), Math.min(previewHash.length(), "sha256:".length() + 16));
    }

    public String candidateId(MlCandidateWindowKind kind) {
        return "candidate-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public List<String> distinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public long duplicateTimestampCount(List<TelemetryChartResponse.Point> points) {
        Set<Instant> seen = new LinkedHashSet<>();
        long duplicates = 0;
        for (TelemetryChartResponse.Point point : points) {
            if (!seen.add(point.timestamp())) {
                duplicates++;
            }
        }
        return duplicates;
    }

    public boolean isMlExcluded(MlDatasetTelemetryRow row, List<MaintenanceWindowReadModel> windows) {
        return windows.stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                .anyMatch(window -> !row.timestamp().isBefore(window.getStartAt()) && row.timestamp().isBefore(window.getEndAt()));
    }

    public MlLabelType labelType(MaintenanceOutcomeReadModel outcome) {
        return switch (outcome.getOutcomeType()) {
            case "CONFIRMED_FAILURE" -> MlLabelType.CONFIRMED_FAILURE;
            case "CORRECTIVE_MAINTENANCE" -> MlLabelType.CORRECTIVE_MAINTENANCE;
            case "PREVENTIVE_MAINTENANCE" -> MlLabelType.PREVENTIVE_MAINTENANCE;
            case "INSPECTION_NORMAL" -> MlLabelType.INSPECTION_NORMAL;
            case "FALSE_POSITIVE" -> MlLabelType.FALSE_POSITIVE;
            case "SENSOR_FAULT" -> MlLabelType.SENSOR_FAULT;
            case "UNRESOLVED_ANOMALY" -> MlLabelType.UNRESOLVED_ANOMALY;
            default -> MlLabelType.UNKNOWN;
        };
    }

    public boolean isPositive(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    public long longQualityValue(Map<String, Object> qualityProfile, String key) {
        Object value = qualityProfile.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> longMapFromQualityProfile(Map<String, Object> qualityProfile, String key) {
        Object value = qualityProfile.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String stringKey && entry.getValue() instanceof Number numberValue) {
                result.put(stringKey, numberValue.longValue());
            }
        }
        return result;
    }

    public Map<String, Long> sortedLongMap(Map<String, Long> values) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    public Map<String, List<String>> sortedUuidListMap(Map<UUID, List<UUID>> values) {
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(
                        entry.getKey().toString(),
                        entry.getValue().stream().map(UUID::toString).sorted().toList()));
        return sorted;
    }

    public Map<String, Object> labelReadinessMap(MlDatasetLabelReadinessDto labelReadiness) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("horizonDays", labelReadiness.horizonDays());
        value.put("positiveLabels", labelReadiness.positiveLabels());
        value.put("normalLabels", labelReadiness.normalLabels());
        value.put("unresolvedLabels", labelReadiness.unresolvedLabels());
        value.put("unobservableRows", labelReadiness.unobservableRows());
        value.put("maintenanceExcludedLabels", labelReadiness.maintenanceExcludedLabels());
        value.put("coverageRatio", labelReadiness.coverageRatio());
        value.put("status", labelReadiness.status().name());
        return value;
    }

    public Instant maxInstant(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    public Instant minInstant(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    public MlDatasetNormalizedMetric normalizeMetric(String metricName) {
        String metric = metricName == null ? "" : metricName.trim();
        return switch (metric) {
            case "activePowerW", "active_power_w", "active_power" -> new MlDatasetNormalizedMetric(metric, "active_power", "W");
            case "voltage", "voltageV", "voltage_v" -> new MlDatasetNormalizedMetric(metric, "voltage", "V");
            case "current", "currentA", "current_a" -> new MlDatasetNormalizedMetric(metric, "current", "A");
            case "energyGrowth", "energy_growth", "energyGrowthWh", "energy_growth_wh" -> new MlDatasetNormalizedMetric(metric, "energy_growth", "Wh");
            case "temperature", "temperatureC", "temperature_c" -> new MlDatasetNormalizedMetric(metric, "temperature", "C");
            case "vibration", "vibrationMmS", "vibration_mm_s" -> new MlDatasetNormalizedMetric(metric, "vibration", "mm/s");
            case "runtime", "runtimeHours", "runtime_hours" -> new MlDatasetNormalizedMetric(metric, "runtime", "h");
            case "powerFactor", "power_factor" -> new MlDatasetNormalizedMetric(metric, "power_factor", "ratio");
            case "frequency", "frequencyHz", "frequency_hz" -> new MlDatasetNormalizedMetric(metric, "frequency", "Hz");
            default -> new MlDatasetNormalizedMetric(metric, toSnakeCase(metric), null);
        };
    }

    public long maxGapSeconds(List<TelemetryChartResponse.Point> points) {
        long max = 0;
        for (int index = 1; index < points.size(); index++) {
            long gap = Math.abs(Duration.between(points.get(index - 1).timestamp(), points.get(index).timestamp()).toSeconds());
            max = Math.max(max, gap);
        }
        return max;
    }

    public void increment(Map<String, Long> values, String key) {
        increment(values, key, 1);
    }

    public void increment(Map<String, Long> values, String key, long amount) {
        values.merge(key, amount, Long::sum);
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML dataset metadata");
        }
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        char previous = 0;
        for (char current : value.toCharArray()) {
            if (Character.isUpperCase(current) && builder.length() > 0 && previous != '_' && previous != '-') {
                builder.append('_');
            }
            if (current == '-' || current == ' ' || current == '.') {
                builder.append('_');
            } else {
                builder.append(Character.toLowerCase(current));
            }
            previous = current;
        }
        return builder.toString().replaceAll("_+", "_").toLowerCase(Locale.ROOT);
    }
}
