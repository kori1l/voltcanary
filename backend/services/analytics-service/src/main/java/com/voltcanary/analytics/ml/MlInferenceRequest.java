package com.voltcanary.analytics.ml;

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
                onlineFeatureStatus(metrics, modelPreference),
                onlineFeatureErrors(metrics, modelPreference));
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
        if (hasPrecomputedFeatureVector(metrics)) {
            return new LinkedHashMap<>(metrics);
        }
        Map<String, Object> features = new LinkedHashMap<>();
        String metricName = stringValue(metrics, "metric");
        double latestValue = numberValue(metrics, "latestValue");
        double baselineMean = numberValue(baseline, "mean");
        double loadRatio = baselineMean == 0 ? 0 : latestValue / baselineMean;
        features.put("deviceKey", deviceId == null ? "" : deviceId.toString());
        features.put("metricName", metricName);
        features.put("timestamp", timestamp == null ? "" : timestamp.toString());
        features.put("rollingMean", numberValue(metrics, "mean"));
        features.put("rollingMin", numberValue(metrics, "min"));
        features.put("rollingMax", numberValue(metrics, "max"));
        features.put("rollingStddev", 0.0);
        features.put("baselineDeviationRatio", loadRatio);
        features.put("missingGapFlag", numberValue(recentWindow, "sampleCount") <= 0);
        features.put("samplingGapSeconds", 0);
        features.put("hourOfDay", timestamp == null ? null : timestamp.atZone(ZoneOffset.UTC).getHour());
        features.put("dayOfWeek", timestamp == null ? null : timestamp.atZone(ZoneOffset.UTC).getDayOfWeek().getValue());
        features.put("deviceType", stringValue(metrics, "deviceType"));
        features.put("metricSchemaId", metricName.isBlank() ? "" : metricName + ":" + stringValue(metrics, "metricUnit"));
        features.put("applianceLoadRatio", loadRatio);
        features.put("applianceActivityFlag", latestValue > baselineMean);
        features.put("maintenanceOverlapFlag", excludedIntervals != null && !excludedIntervals.isEmpty());
        features.put("qualityFlags", qualityFlags(metrics, recentWindow, excludedIntervals));
        return features;
    }

    private static Map<String, Object> onlineFeatureContract(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        FeatureVectorContract vectorContract = onlineVectorContract(metrics, modelPreference);
        Map<String, Object> contract = vectorContract.metadata(
                featureSetVersion(metrics),
                generatorVersion(metrics),
                "",
                "");
        contract.put("generatedFeatureSchemaHash", stringValue(contract, "contractMetadataHash"));
        contract.put("requiredFeatureSchemaHash", stringValue(modelPreference, "requiredFeatureSchemaHash"));
        contract.put("requiredFeatureContractVersion", stringValue(modelPreference, "requiredFeatureContractVersion"));
        contract.put("requiredFeatureContractMetadataHash", stringValue(modelPreference, "requiredFeatureContractMetadataHash"));
        contract.put("requiredFeatureSetVersion", stringValue(modelPreference, "requiredFeatureSetVersion"));
        contract.put("requiredFeatureGeneratorVersion", stringValue(modelPreference, "requiredFeatureGeneratorVersion"));
        contract.put("requiredFeatureTemplateId", stringValue(modelPreference, "requiredFeatureTemplateId"));
        contract.put("requiredFeatureTemplateVersion", stringValue(modelPreference, "requiredFeatureTemplateVersion"));
        contract.put("requiredHorizonDays", stringValue(modelPreference, "requiredHorizonDays"));
        contract.put("requiredFeatureConfigHash", stringValue(modelPreference, "requiredFeatureConfigHash"));
        contract.put("featureSchemaHash", stringValue(contract, "contractMetadataHash"));
        contract.put("metricSchemaId", stringValue(metrics, "metric").isBlank()
                ? ""
                : stringValue(metrics, "metric") + ":" + stringValue(metrics, "metricUnit"));
        return contract;
    }

    private static String onlineFeatureStatus(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        List<String> errors = onlineFeatureErrors(metrics, modelPreference);
        if (errors.isEmpty()) {
            return "READY";
        }
        if (errors.stream().anyMatch(error -> error.startsWith("FEATURE_SCHEMA_MISMATCH"))) {
            return "FEATURE_SCHEMA_MISMATCH";
        }
        boolean contractMismatch = errors.stream().anyMatch(error ->
                error.startsWith("FEATURE_CONTRACT_VERSION_MISMATCH")
                        || error.startsWith("FEATURE_TEMPLATE_MISMATCH")
                        || error.startsWith("FEATURE_SET_VERSION_MISMATCH")
                        || error.startsWith("FEATURE_GENERATOR_VERSION_MISMATCH")
                        || error.startsWith("FEATURE_HORIZON_MISMATCH"));
        return contractMismatch ? "FEATURE_CONTRACT_VERSION_MISMATCH" : "FEATURES_UNAVAILABLE";
    }

    private static List<String> onlineFeatureErrors(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        if (hasPrecomputedFeatureVector(metrics)) {
            return contractCompatibilityErrors(metrics, modelPreference);
        }
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
        errors.addAll(contractCompatibilityErrors(metrics, modelPreference));
        return List.copyOf(errors);
    }

    private static List<String> contractCompatibilityErrors(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> generated = onlineFeatureContract(metrics, modelPreference);
        compareString(errors, "FEATURE_CONTRACT_VERSION_MISMATCH", "contractVersion", stringValue(generated, "contractVersion"), stringValue(modelPreference, "requiredFeatureContractVersion"));
        compareString(errors, "FEATURE_SET_VERSION_MISMATCH", "featureSetVersion", stringValue(generated, "featureSetVersion"), stringValue(modelPreference, "requiredFeatureSetVersion"));
        compareString(errors, "FEATURE_GENERATOR_VERSION_MISMATCH", "generatorVersion", stringValue(generated, "generatorVersion"), stringValue(modelPreference, "requiredFeatureGeneratorVersion"));
        compareString(errors, "FEATURE_TEMPLATE_MISMATCH", "featureTemplateId", stringValue(generated, "featureTemplateId"), stringValue(modelPreference, "requiredFeatureTemplateId"));
        compareString(errors, "FEATURE_TEMPLATE_MISMATCH", "featureTemplateVersion", stringValue(generated, "featureTemplateVersion"), stringValue(modelPreference, "requiredFeatureTemplateVersion"));
        compareString(errors, "FEATURE_HORIZON_MISMATCH", "horizonDays", stringValue(generated, "horizonDays"), stringValue(modelPreference, "requiredHorizonDays"));
        String requiredContractHash = stringValue(modelPreference, "requiredFeatureContractMetadataHash");
        String generatedContractHash = stringValue(generated, "contractMetadataHash");
        if (!requiredContractHash.isBlank() && !requiredContractHash.equals(generatedContractHash)) {
            errors.add("FEATURE_SCHEMA_MISMATCH: required contractMetadataHash " + requiredContractHash + " generated " + generatedContractHash);
        }
        return List.copyOf(errors);
    }

    private static void compareString(List<String> errors, String code, String field, String generated, String required) {
        if (!required.isBlank() && !required.equals(generated)) {
            errors.add(code + ": " + field + " required " + required + " generated " + generated);
        }
    }

    private static FeatureVectorContract onlineVectorContract(Map<String, Object> metrics, Map<String, Object> modelPreference) {
        String modelFamily = stringValue(modelPreference, "modelFamily").isBlank()
                ? "ANOMALY_DETECTION"
                : stringValue(modelPreference, "modelFamily");
        Integer horizonDays = integerValue(metrics, "horizonDays");
        if (horizonDays == null && "FAILURE_PREDICTION".equals(modelFamily)) {
            horizonDays = 1;
        }
        String templateId = stringValue(metrics, "featureTemplateId").isBlank()
                ? FeatureTemplateCatalog.DEFAULT_TEMPLATE_ID
                : stringValue(metrics, "featureTemplateId");
        String templateVersion = stringValue(metrics, "featureTemplateVersion").isBlank()
                ? FeatureTemplateCatalog.DEFAULT_TEMPLATE_VERSION
                : stringValue(metrics, "featureTemplateVersion");
        return FeatureVectorContract.forConfig(
                modelFamily,
                horizonDays,
                templateId,
                templateVersion,
                onlineFeatureFamilies(modelFamily));
    }

    private static List<String> onlineFeatureFamilies(String modelFamily) {
        if ("FAILURE_PREDICTION".equals(modelFamily)) {
            return List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context");
        }
        return List.of("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context");
    }

    private static String featureSetVersion(Map<String, Object> metrics) {
        String value = stringValue(metrics, "featureSetVersion");
        return value.isBlank() ? FeatureVectorContract.DEFAULT_FEATURE_SET_VERSION : value;
    }

    private static String generatorVersion(Map<String, Object> metrics) {
        String value = stringValue(metrics, "featureGeneratorVersion");
        return value.isBlank() ? FeatureVectorContract.DEFAULT_GENERATOR_VERSION : value;
    }

    private static boolean hasPrecomputedFeatureVector(Map<String, Object> metrics) {
        return hasNumber(metrics, "rollingMean")
                && hasNumber(metrics, "baselineDeviationRatio")
                && hasNumber(metrics, "applianceLoadRatio");
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

    private static Integer integerValue(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
