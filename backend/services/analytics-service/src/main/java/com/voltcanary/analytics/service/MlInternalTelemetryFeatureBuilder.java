package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MlInternalTelemetryFeatureBuilder {

    private final MlFeatureTemplateAppender templateAppender;

    public MlFeatureGeneration generate(
        MlDatasetVersion datasetVersion,
        MlFeatureConfig config,
        FeatureVectorContract contract,
        MlFeatureInputs inputs
    ) {
        List<MlCanonicalMetricRow> rows = inputs
            .internalRows()
            .stream()
            .sorted(
                Comparator.comparing((MlCanonicalMetricRow row) ->
                    row.getDeviceId() == null ? "" : row.getDeviceId().toString()
                )
                    .thenComparing(row ->
                        row.getVirtualDeviceKey() == null ? "" : row.getVirtualDeviceKey()
                    )
                    .thenComparing(MlCanonicalMetricRow::getCanonicalMetricName)
                    .thenComparing(MlCanonicalMetricRow::getTimestamp)
            )
            .toList();

        var partitions = MlFeaturePartitions.assign(
            datasetVersion,
            rows,
            MlCanonicalMetricRow::getId,
            MlCanonicalMetricRow::getTimestamp,
            row ->
                row.getDeviceId() == null ? row.getVirtualDeviceKey() : row.getDeviceId().toString()
        );
        Map<String, List<MlCanonicalMetricRow>> groups = rows
            .stream()
            .collect(
                Collectors.groupingBy(
                    row -> internalEntityId(row) + "|" + row.getCanonicalMetricName(),
                    LinkedHashMap::new,
                    Collectors.toList()
                )
            );
        MlFeatureLabelLookup labels = MlFeatureLabelLookup.from(inputs.labels());

        List<MlFeatureRowData> featureRows = new java.util.ArrayList<>();
        for (List<MlCanonicalMetricRow> groupRows : groups.values()) {
            double observedSum = 0;
            for (int index = 0; index < groupRows.size(); index++) {
                MlCanonicalMetricRow row = groupRows.get(index);
                observedSum += row.getNumericValue();
                double baselineMean = observedSum / (index + 1);
                int from = Math.max(0, index - config.rollingWindowSize() + 1);
                List<MlCanonicalMetricRow> window = groupRows.subList(from, index + 1);
                Map<String, Object> features = new LinkedHashMap<>();
                features.put("deviceKey", internalEntityId(row));
                features.put("metricName", row.getCanonicalMetricName());
                features.put("timestamp", row.getTimestamp().toString());
                if (config.enabledFeatureFamilies().contains("rolling_stats")) {
                    features.put("rollingMean", mean(window));
                    features.put(
                        "rollingMin",
                        window
                            .stream()
                            .mapToDouble(MlCanonicalMetricRow::getNumericValue)
                            .min()
                            .orElse(row.getNumericValue())
                    );
                    features.put(
                        "rollingMax",
                        window
                            .stream()
                            .mapToDouble(MlCanonicalMetricRow::getNumericValue)
                            .max()
                            .orElse(row.getNumericValue())
                    );
                    features.put("rollingStddev", stddev(window));
                }
                if (config.enabledFeatureFamilies().contains("baseline_deviation")) {
                    features.put(
                        "baselineDeviationRatio",
                        baselineMean == 0 ? 0 : row.getNumericValue() / baselineMean
                    );
                }
                if (config.enabledFeatureFamilies().contains("missing_data")) {
                    long gap =
                        index == 0
                            ? 0
                            : Duration.between(
                                  groupRows.get(index - 1).getTimestamp(),
                                  row.getTimestamp()
                              ).toSeconds();
                    features.put("missingGapFlag", gap > config.missingGapSeconds());
                    features.put("samplingGapSeconds", Math.max(0, gap));
                }
                if (config.enabledFeatureFamilies().contains("time")) {
                    features.put("hourOfDay", row.getTimestamp().atZone(ZoneOffset.UTC).getHour());
                    features.put(
                        "dayOfWeek",
                        row.getTimestamp().atZone(ZoneOffset.UTC).getDayOfWeek().getValue()
                    );
                }
                if (config.enabledFeatureFamilies().contains("device_context")) {
                    features.put("deviceType", row.getDeviceType());
                    features.put(
                        "metricSchemaId",
                        row.getCanonicalMetricName() + ":" + nullToBlank(row.getCanonicalUnit())
                    );
                }
                templateAppender.addTemplateFeatures(
                    features,
                    config,
                    row.getDeviceType(),
                    row.getCanonicalMetricName(),
                    row.getNumericValue(),
                    baselineMean
                );
                featureRows.add(
                    new MlFeatureRowData(
                        row.getDeviceId(),
                        row.getVirtualDeviceKey(),
                        row.getDeviceType(),
                        row.getTimestamp(),
                        config.horizonDays(),
                        internalEntityId(row) + "|" + row.getCanonicalMetricName(),
                        features,
                        labels.valuesFor(
                            row.getDeviceId(),
                            row.getExternalDeviceKey(),
                            row.getMetricName(),
                            row.getTimestamp(),
                            config.horizonDays()
                        ),
                        partitions.get(row.getId()),
                        Map.of(
                            "source",
                            "ml_canonical_metric_rows",
                            "canonicalRowId",
                            row.getId().toString(),
                            "sourceType",
                            row.getSourceType().name()
                        ),
                        qualityFlags(row)
                    )
                );
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sourceType", datasetVersion.getSourceType().name());
        schema.put(
            "contract",
            contract.metadata(config.featureSetVersion(), config.codeConfigVersion(), "", "")
        );
        schema.put("groupCount", groups.size());
        return new MlFeatureGeneration(featureRows.size(), 0, schema, featureRows);
    }

    private double mean(List<MlCanonicalMetricRow> rows) {
        return rows.stream().mapToDouble(MlCanonicalMetricRow::getNumericValue).average().orElse(0);
    }

    private double stddev(List<MlCanonicalMetricRow> rows) {
        double mean = mean(rows);
        return Math.sqrt(
            rows
                .stream()
                .mapToDouble(row -> Math.pow(row.getNumericValue() - mean, 2))
                .average()
                .orElse(0)
        );
    }

    private String internalEntityId(MlCanonicalMetricRow row) {
        if (row.getDeviceId() != null) {
            return row.getDeviceId().toString();
        }
        if (row.getVirtualDeviceKey() != null && !row.getVirtualDeviceKey().isBlank()) {
            return row.getVirtualDeviceKey();
        }
        return row.getExternalDeviceKey();
    }

    private List<String> qualityFlags(MlCanonicalMetricRow row) {
        if (
            row.getQualityFlags() == null ||
            row.getQualityFlags().isBlank() ||
            "[]".equals(row.getQualityFlags())
        ) {
            return List.of();
        }
        return List.of(row.getQualityFlags());
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
