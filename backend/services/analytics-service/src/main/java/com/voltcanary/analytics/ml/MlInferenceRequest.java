package com.voltcanary.analytics.ml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlInferenceRequest(
        UUID deviceId,
        String timestamp,
        Map<String, Object> metrics,
        List<MlExcludedInterval> excludedIntervals,
        Map<String, Object> baseline,
        Map<String, Object> recentWindow,
        Map<String, Object> modelPreference,
        Map<String, Object> featureVector,
        Map<String, Object> featureContract,
        String featureGenerationStatus,
        List<String> featureGenerationErrors) {

    public MlInferenceRequest(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<MlExcludedInterval> excludedIntervals,
            Map<String, Object> baseline,
            Map<String, Object> recentWindow,
            Map<String, Object> modelPreference) {
        this(
                deviceId,
                timestamp == null ? null : timestamp.toString(),
                metrics,
                excludedIntervals,
                baseline,
                recentWindow,
                modelPreference,
                onlineFeatureVector(deviceId, timestamp, metrics, baseline, recentWindow, excludedIntervals),
                onlineFeatureContract(metrics, modelPreference),
                onlineFeatureErrors(metrics).isEmpty() ? "READY" : "FEATURES_UNAVAILABLE",
                onlineFeatureErrors(metrics));
    }

    public MlInferenceRequest(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        this(deviceId, timestamp, metrics, List.of());
    }

    public MlInferenceRequest(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<MlExcludedInterval> excludedIntervals) {
        this(deviceId, timestamp, metrics, excludedIntervals, null, null, null);
    }

    private static Map<String, Object> onlineFeatureVector(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            Map<String, Object> baseline,
            Map<String, Object> recentWindow,
            List<MlExcludedInterval> excludedIntervals) {
        Map<String, Object> features = new LinkedHashMap<>();
        String metricName = stringValue(metrics, "metric");
        double latestValue = numberValue(metrics, "latestValue");
        double baselineMean = numberValue(baseline, "mean");
        features.put("deviceKey", deviceId == null ? "" : deviceId.toString());
        features.put("metricName", metricName);
        features.put("timestamp", timestamp == null ? "" : timestamp.toString());
        features.put("rollingMean", numberValue(metrics, "mean"));
        features.put("rollingMin", numberValue(metrics, "min"));
        features.put("rollingMax", numberValue(metrics, "max"));
        features.put("rollingStddev", 0.0);
        features.put("baselineDeviationRatio", baselineMean == 0 ? 0 : latestValue / baselineMean);
        features.put("missingGapFlag", numberValue(recentWindow, "sampleCount") <= 0);
        features.put("samplingGapSeconds", 0);
        features.put("hourOfDay", timestamp == null ? null : timestamp.atZone(ZoneOffset.UTC).getHour());
        features.put("dayOfWeek", timestamp == null ? null : timestamp.atZone(ZoneOffset.UTC).getDayOfWeek().getValue());
        features.put("deviceType", stringValue(metrics, "deviceType"));
        features.put("metricSchemaId", metricName.isBlank() ? "" : metricName + ":" + stringValue(metrics, "metricUnit"));
        features.put("maintenanceOverlapFlag", excludedIntervals != null && !excludedIntervals.isEmpty());
        features.put("qualityFlags", qualityFlags(metrics, recentWindow, excludedIntervals));
        return features;
    }

    private static Map<String, Object> onlineFeatureContract(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        Map<String, Object> contract = new LinkedHashMap<>();
        List<String> generatedColumns = List.of(
                "deviceKey",
                "metricName",
                "timestamp",
                "rollingMean",
                "rollingMin",
                "rollingMax",
                "rollingStddev",
                "baselineDeviationRatio",
                "missingGapFlag",
                "samplingGapSeconds",
                "hourOfDay",
                "dayOfWeek",
                "deviceType",
                "metricSchemaId",
                "maintenanceOverlapFlag",
                "qualityFlags");
        contract.put("contractVersion", "feature-vector-contract-v1");
        contract.put("featureSetVersion", "feature-set-v1");
        contract.put("featureTemplateId", "generic-appliance");
        contract.put("featureTemplateVersion", "template-v1");
        contract.put("modelFamily", stringValue(modelPreference, "modelFamily").isBlank()
                ? "ANOMALY_DETECTION"
                : stringValue(modelPreference, "modelFamily"));
        contract.put("requiredFeatureSchemaHash", stringValue(modelPreference, "requiredFeatureSchemaHash"));
        contract.put("featureSchemaHash", sha256(String.join("|", generatedColumns)));
        contract.put("generatedColumns", generatedColumns);
        contract.put("requiredColumns", List.of(
                "deviceKey",
                "metricName",
                "timestamp",
                "rollingMean",
                "baselineDeviationRatio",
                "samplingGapSeconds"));
        contract.put("metricSchemaId", stringValue(metrics, "metric").isBlank()
                ? ""
                : stringValue(metrics, "metric") + ":" + stringValue(metrics, "metricUnit"));
        return contract;
    }

    private static List<String> onlineFeatureErrors(Map<String, Object> metrics) {
        List<String> errors = new ArrayList<>();
        if (stringValue(metrics, "metric").isBlank()) {
            errors.add("MISSING_REQUIRED_FEATURE: metricName");
        }
        if (!hasNumber(metrics, "latestValue")) {
            errors.add("MISSING_REQUIRED_FEATURE: latestValue");
        }
        if (!hasNumber(metrics, "mean")) {
            errors.add("MISSING_REQUIRED_FEATURE: rollingMean");
        }
        return List.copyOf(errors);
    }

    private static List<String> qualityFlags(
            Map<String, Object> metrics,
            Map<String, Object> recentWindow,
            List<MlExcludedInterval> excludedIntervals) {
        List<String> flags = new ArrayList<>();
        if (stringValue(metrics, "deviceType").isBlank()) {
            flags.add("DEVICE_TYPE_UNKNOWN");
        }
        if (numberValue(recentWindow, "sampleCount") <= 0) {
            flags.add("EMPTY_ACTIVE_WINDOW");
        }
        if (excludedIntervals != null && !excludedIntervals.isEmpty()) {
            flags.add("MAINTENANCE_EXCLUDED");
        }
        return List.copyOf(flags);
    }

    private static boolean hasNumber(Map<String, Object> values, String key) {
        return values != null && values.get(key) instanceof Number;
    }

    private static double numberValue(Map<String, Object> values, String key) {
        return hasNumber(values, key) ? ((Number) values.get(key)).doubleValue() : 0;
    }

    private static String stringValue(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : value.toString();
    }

    private static String sha256(String value) {
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
}
