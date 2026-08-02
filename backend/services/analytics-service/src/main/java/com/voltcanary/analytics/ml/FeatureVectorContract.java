package com.voltcanary.analytics.ml;

import com.voltcanary.analytics.domain.MlModelFamily;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record FeatureVectorContract(
        String contractVersion,
        MlModelFamily modelFamily,
        Integer horizonDays,
        String featureTemplateId,
        String featureTemplateVersion,
        List<String> enabledFeatureFamilies,
        List<String> generatedColumns,
        List<String> requiredColumns,
        List<String> optionalColumns,
        List<String> labelColumns) {

    public static FeatureVectorContract forConfig(
            String modelFamily,
            Integer horizonDays,
            String featureTemplateId,
            String featureTemplateVersion,
            List<String> enabledFeatureFamilies) {
        MlModelFamily family = parseFamily(modelFamily);
        if (family == MlModelFamily.FAILURE_PREDICTION && horizonDays == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FAILURE_PREDICTION features require horizonDays");
        }

        List<String> families = List.copyOf(enabledFeatureFamilies);
        FeatureTemplateCatalog.FeatureTemplate template = FeatureTemplateCatalog.resolve(featureTemplateId, featureTemplateVersion);
        List<String> generated = withTemplateColumns(generatedColumns(families), template.generatedColumns());
        List<String> required = withTemplateColumns(requiredColumns(family, families), template.requiredColumns());
        List<String> optional = generated.stream()
                .filter(column -> !required.contains(column))
                .toList();
        List<String> labels = labelColumns(family);

        return new FeatureVectorContract(
                "feature-vector-contract-v1",
                family,
                horizonDays,
                template.id(),
                template.version(),
                families,
                generated,
                required,
                optional,
                labels);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", contractVersion);
        value.put("modelFamily", modelFamily.name());
        value.put("horizonDays", horizonDays);
        value.put("featureTemplateId", featureTemplateId);
        value.put("featureTemplateVersion", featureTemplateVersion);
        value.put("featureTemplate", FeatureTemplateCatalog.resolve(featureTemplateId, featureTemplateVersion).asMap());
        value.put("enabledFeatureFamilies", enabledFeatureFamilies);
        value.put("generatedColumns", generatedColumns);
        value.put("requiredColumns", requiredColumns);
        value.put("optionalColumns", optionalColumns);
        value.put("labelColumns", labelColumns);
        return value;
    }

    private static MlModelFamily parseFamily(String modelFamily) {
        try {
            return MlModelFamily.valueOf(modelFamily.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported ML model family: " + modelFamily);
        }
    }

    private static List<String> generatedColumns(List<String> families) {
        List<String> columns = new ArrayList<>(List.of("deviceKey", "metricName", "timestamp"));
        if (families.contains("rolling_stats")) {
            columns.addAll(List.of("rollingMean", "rollingMin", "rollingMax", "rollingStddev"));
        }
        if (families.contains("robust_zscore")) {
            columns.add("robustZScore");
        }
        if (families.contains("baseline_deviation")) {
            columns.add("baselineDeviationRatio");
        }
        if (families.contains("trend")) {
            columns.addAll(List.of("trendSlope", "trendDirection"));
        }
        if (families.contains("drift")) {
            columns.addAll(List.of("ewmaValue", "cusumPositive", "cusumNegative"));
        }
        if (families.contains("runtime")) {
            columns.addAll(List.of("runtimeSeconds", "idleSeconds", "dutyCycleRatio"));
        }
        if (families.contains("cycle")) {
            columns.addAll(List.of("cycleCount", "cycleMeanSeconds", "cycleStddevSeconds"));
        }
        if (families.contains("missing_data")) {
            columns.addAll(List.of("missingGapFlag", "samplingGapSeconds"));
        }
        if (families.contains("voltage_current_instability")) {
            columns.addAll(List.of("voltageStddev", "currentStddev", "powerFactorEstimate"));
        }
        if (families.contains("maintenance")) {
            columns.addAll(List.of("maintenanceOverlapFlag", "daysSinceMaintenance", "daysUntilPlannedMaintenance"));
        }
        if (families.contains("time")) {
            columns.addAll(List.of("hourOfDay", "dayOfWeek"));
        }
        if (families.contains("device_context")) {
            columns.addAll(List.of("deviceType", "metricSchemaId"));
        }
        if (families.contains("label")) {
            columns.add("label");
        }
        return List.copyOf(columns);
    }

    private static List<String> requiredColumns(MlModelFamily family, List<String> families) {
        List<String> columns = new ArrayList<>(List.of("deviceKey", "metricName", "timestamp"));
        switch (family) {
            case ANOMALY_DETECTION -> addIfFamilyEnabled(columns, families, Map.of(
                    "rolling_stats", List.of("rollingMean", "rollingStddev"),
                    "baseline_deviation", List.of("baselineDeviationRatio"),
                    "missing_data", List.of("missingGapFlag", "samplingGapSeconds"),
                    "time", List.of("hourOfDay", "dayOfWeek")));
            case DEGRADATION_SCORING -> addIfFamilyEnabled(columns, families, Map.of(
                    "rolling_stats", List.of("rollingMean", "rollingStddev"),
                    "baseline_deviation", List.of("baselineDeviationRatio"),
                    "trend", List.of("trendSlope"),
                    "drift", List.of("ewmaValue"),
                    "maintenance", List.of("daysSinceMaintenance"),
                    "runtime", List.of("dutyCycleRatio")));
            case FAILURE_PREDICTION -> addIfFamilyEnabled(columns, families, Map.of(
                    "rolling_stats", List.of("rollingMean", "rollingStddev"),
                    "baseline_deviation", List.of("baselineDeviationRatio"),
                    "trend", List.of("trendSlope"),
                    "drift", List.of("ewmaValue", "cusumPositive", "cusumNegative"),
                    "maintenance", List.of("daysSinceMaintenance", "daysUntilPlannedMaintenance"),
                    "missing_data", List.of("samplingGapSeconds"),
                    "device_context", List.of("deviceType")));
            case FORECASTING -> addIfFamilyEnabled(columns, families, Map.of(
                    "rolling_stats", List.of("rollingMean", "rollingMin", "rollingMax"),
                    "time", List.of("hourOfDay", "dayOfWeek"),
                    "missing_data", List.of("samplingGapSeconds")));
        }
        return List.copyOf(columns);
    }

    private static void addIfFamilyEnabled(
            List<String> columns,
            List<String> enabledFamilies,
            Map<String, List<String>> columnsByFamily) {
        columnsByFamily.entrySet().stream()
                .filter(entry -> enabledFamilies.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(column -> !columns.contains(column))
                .forEach(columns::add);
    }

    private static List<String> withTemplateColumns(List<String> baseColumns, List<String> templateColumns) {
        List<String> columns = new ArrayList<>(baseColumns);
        templateColumns.stream()
                .filter(column -> !columns.contains(column))
                .forEach(columns::add);
        return List.copyOf(columns);
    }

    private static List<String> labelColumns(MlModelFamily family) {
        return switch (family) {
            case ANOMALY_DETECTION -> List.of("label");
            case DEGRADATION_SCORING -> List.of("degradationLabel");
            case FAILURE_PREDICTION -> List.of("failureWithinHorizon", "failureOutcomeType");
            case FORECASTING -> List.of("targetValue");
        };
    }
}
