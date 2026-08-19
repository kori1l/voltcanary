package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MlImportedDatasetFeatureBuilder {

    private final MlImportedFileRepository importedFileRepository;
    private final MlImportedMetricRowRepository importedMetricRowRepository;
    private final MlLabelRepository labelRepository;
    private final MlFeatureTemplateAppender templateAppender;

    public MlImportedDatasetFeatureBuilder(
            MlImportedFileRepository importedFileRepository,
            MlImportedMetricRowRepository importedMetricRowRepository,
            MlLabelRepository labelRepository,
            MlFeatureTemplateAppender templateAppender) {
        this.importedFileRepository = importedFileRepository;
        this.importedMetricRowRepository = importedMetricRowRepository;
        this.labelRepository = labelRepository;
        this.templateAppender = templateAppender;
    }

    public MlFeatureGeneration generate(
            MlDatasetVersion datasetVersion,
            MlFeatureConfig config,
            FeatureVectorContract contract) {
        List<MlImportedMetricRow> rows = importedFileRepository.findByDatasetIdOrderByImportedAtDesc(datasetVersion.getDatasetId()).stream()
                .map(MlImportedFile::getId)
                .flatMap(importedFileId -> importedMetricRowRepository.findByImportedFileIdOrderBySourceRowNumber(importedFileId).stream())
                .sorted(Comparator.comparing(MlImportedMetricRow::getExternalDeviceKey)
                        .thenComparing(MlImportedMetricRow::getMetricName)
                        .thenComparing(MlImportedMetricRow::getTimestamp)
                        .thenComparingLong(MlImportedMetricRow::getSourceRowNumber))
                .toList();

        Map<String, List<MlImportedMetricRow>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.getExternalDeviceKey() + "|" + row.getMetricName(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        MlFeatureLabelLookup labels = MlFeatureLabelLookup.from(labelRepository.findByAccountIdAndDatasetVersionId(
                datasetVersion.getAccountId(),
                datasetVersion.getId()));

        long rejected = 0;
        List<MlFeatureRowData> featureRows = new java.util.ArrayList<>();
        for (List<MlImportedMetricRow> groupRows : groups.values()) {
            double baselineMean = groupRows.stream().mapToDouble(MlImportedMetricRow::getNumericValue).average().orElse(0);
            for (int index = 0; index < groupRows.size(); index++) {
                MlImportedMetricRow row = groupRows.get(index);
                int from = Math.max(0, index - config.rollingWindowSize() + 1);
                List<MlImportedMetricRow> window = groupRows.subList(from, index + 1);
                Map<String, Object> features = new LinkedHashMap<>();
                features.put("deviceKey", row.getExternalDeviceKey());
                features.put("metricName", row.getMetricName());
                features.put("timestamp", row.getTimestamp().toString());
                if (config.enabledFeatureFamilies().contains("rolling_stats")) {
                    features.put("rollingMean", mean(window));
                    features.put("rollingMin", window.stream().mapToDouble(MlImportedMetricRow::getNumericValue).min().orElse(row.getNumericValue()));
                    features.put("rollingMax", window.stream().mapToDouble(MlImportedMetricRow::getNumericValue).max().orElse(row.getNumericValue()));
                    features.put("rollingStddev", stddev(window));
                }
                if (config.enabledFeatureFamilies().contains("baseline_deviation")) {
                    features.put("baselineDeviationRatio", baselineMean == 0 ? 0 : row.getNumericValue() / baselineMean);
                }
                if (config.enabledFeatureFamilies().contains("missing_data")) {
                    long gap = index == 0 ? 0 : Duration.between(groupRows.get(index - 1).getTimestamp(), row.getTimestamp()).toSeconds();
                    features.put("missingGapFlag", gap > config.missingGapSeconds());
                    features.put("samplingGapSeconds", Math.max(0, gap));
                }
                if (config.enabledFeatureFamilies().contains("time")) {
                    features.put("hourOfDay", row.getTimestamp().atZone(ZoneOffset.UTC).getHour());
                    features.put("dayOfWeek", row.getTimestamp().atZone(ZoneOffset.UTC).getDayOfWeek().getValue());
                }
                if (config.enabledFeatureFamilies().contains("label")) {
                    features.put("label", row.getLabel() == null ? null : row.getLabel().name());
                }
                templateAppender.addTemplateFeatures(features, config, row.getDeviceType(), row.getMetricName(), row.getNumericValue(), baselineMean);
                Map<String, Object> labelValues = labels.valuesFor(
                        row.getMatchedDeviceId(),
                        row.getExternalDeviceKey(),
                        row.getMetricName(),
                        row.getTimestamp(),
                        config.horizonDays());
                featureRows.add(new MlFeatureRowData(
                        row.getMatchedDeviceId(),
                        row.getVirtualDeviceKey(),
                        row.getDeviceType(),
                        row.getTimestamp(),
                        config.horizonDays(),
                        row.getExternalDeviceKey() + "|" + row.getMetricName(),
                        features,
                        labelValues.isEmpty() && row.getLabel() != null ? Map.of("label", row.getLabel().name()) : labelValues,
                        splitAssignment(index, groupRows.size()),
                        Map.of(
                                "source", "ml_imported_metric_rows",
                                "importedFileId", row.getImportedFileId().toString(),
                                "sourceRowNumber", row.getSourceRowNumber()),
                        List.of()));
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sourceType", datasetVersion.getSourceType().name());
        schema.put("contract", contract.metadata(
                config.featureSetVersion(),
                config.codeConfigVersion(),
                "",
                ""));
        schema.put("groupCount", groups.size());
        return new MlFeatureGeneration(featureRows.size(), rejected, schema, featureRows);
    }

    private double mean(List<MlImportedMetricRow> rows) {
        return rows.stream().mapToDouble(MlImportedMetricRow::getNumericValue).average().orElse(0);
    }

    private double stddev(List<MlImportedMetricRow> rows) {
        double mean = mean(rows);
        return Math.sqrt(rows.stream()
                .mapToDouble(row -> Math.pow(row.getNumericValue() - mean, 2))
                .average()
                .orElse(0));
    }

    private String splitAssignment(int index, int groupSize) {
        if (groupSize <= 1) {
            return "TRAIN";
        }
        double ratio = (double) index / groupSize;
        if (ratio < 0.7) {
            return "TRAIN";
        }
        if (ratio < 0.85) {
            return "VALIDATION";
        }
        return "TEST";
    }
}
