package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlFeaturePipelineService {

    private static final Logger log = LoggerFactory.getLogger(MlFeaturePipelineService.class);

    private static final int DEFAULT_ROLLING_WINDOW_SIZE = 6;
    private static final long DEFAULT_MISSING_GAP_SECONDS = 6 * 60 * 60;
    private static final List<String> DEFAULT_FEATURE_FAMILIES = List.of(
            "rolling_stats",
            "baseline_deviation",
            "missing_data",
            "time",
            "label");

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlImportedFileRepository importedFileRepository;
    private final MlImportedMetricRowRepository importedMetricRowRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public MlFeaturePipelineService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlImportedFileRepository importedFileRepository,
            MlImportedMetricRowRepository importedMetricRowRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.importedFileRepository = importedFileRepository;
        this.importedMetricRowRepository = importedMetricRowRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public MlFeatureSnapshotResponse generate(UUID accountId, MlFeatureSnapshotRequest request) {
        MlDatasetVersion datasetVersion = datasetVersionRepository.findById(request.datasetVersionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(datasetVersion.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        if (datasetVersion.getStatus() != MlDatasetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ML dataset version must be READY");
        }

        FeatureConfig config = FeatureConfig.from(request);
        FeatureGeneration generation = generateFeatures(datasetVersion, config);
        String configJson = writeJson(config.asMap());
        String schemaHash = sha256(writeJson(generation.schema()) + "|" + configJson);

        Instant now = Instant.now();
        MlFeatureSnapshot snapshot = new MlFeatureSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setAccountId(accountId);
        snapshot.setDatasetVersionId(datasetVersion.getId());
        snapshot.setFeatureSetVersion(config.featureSetVersion());
        snapshot.setCodeConfigVersion(config.codeConfigVersion());
        snapshot.setFeatureConfigJson(configJson);
        snapshot.setFeatureSchemaHash(schemaHash);
        snapshot.setRowCount(generation.rowCount());
        snapshot.setRejectedRowCount(generation.rejectedRowCount());
        snapshot.setArtifactUri("db://ml_feature_snapshots/" + snapshot.getId());
        snapshot.setCreatedAt(now);
        featureSnapshotRepository.save(snapshot);
        meterRegistry.counter(
                        "analytics.ml.feature.generation.completed",
                        "source_type", datasetVersion.getSourceType().name(),
                        "feature_set_version", snapshot.getFeatureSetVersion())
                .increment();
        log.info("mlFeatureSnapshotGenerated featureSnapshotId={} datasetVersionId={} accountId={} sourceType={} rowCount={} rejectedRowCount={} featureSchemaHash={}",
                snapshot.getId(), datasetVersion.getId(), accountId, datasetVersion.getSourceType(),
                snapshot.getRowCount(), snapshot.getRejectedRowCount(), snapshot.getFeatureSchemaHash());

        return response(snapshot);
    }

    private FeatureGeneration generateFeatures(MlDatasetVersion datasetVersion, FeatureConfig config) {
        if (datasetVersion.getSourceType() != MlDatasetSourceType.EXTERNAL_IMPORT) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("sourceType", datasetVersion.getSourceType().name());
            schema.put("featureFamilies", config.enabledFeatureFamilies());
            schema.put("generatedColumns", generatedColumns(config.enabledFeatureFamilies()));
            schema.put("input", "dataset_lineage");
            return new FeatureGeneration(0, 0, schema);
        }

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

        long rejected = 0;
        List<Map<String, Object>> sampleRows = new ArrayList<>();
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
                if (sampleRows.size() < 25) {
                    sampleRows.add(features);
                }
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sourceType", datasetVersion.getSourceType().name());
        schema.put("featureFamilies", config.enabledFeatureFamilies());
        schema.put("generatedColumns", generatedColumns(config.enabledFeatureFamilies()));
        schema.put("sampleRows", sampleRows);
        schema.put("groupCount", groups.size());
        return new FeatureGeneration(rows.size(), rejected, schema);
    }

    private List<String> generatedColumns(List<String> families) {
        List<String> columns = new ArrayList<>(List.of("deviceKey", "metricName", "timestamp"));
        if (families.contains("rolling_stats")) {
            columns.addAll(List.of("rollingMean", "rollingMin", "rollingMax", "rollingStddev"));
        }
        if (families.contains("baseline_deviation")) {
            columns.add("baselineDeviationRatio");
        }
        if (families.contains("missing_data")) {
            columns.addAll(List.of("missingGapFlag", "samplingGapSeconds"));
        }
        if (families.contains("maintenance")) {
            columns.add("maintenanceOverlapFlag");
        }
        if (families.contains("time")) {
            columns.addAll(List.of("hourOfDay", "dayOfWeek"));
        }
        if (families.contains("label")) {
            columns.add("label");
        }
        if (families.contains("device_context")) {
            columns.addAll(List.of("deviceType", "metricSchemaId"));
        }
        return columns;
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

    private MlFeatureSnapshotResponse response(MlFeatureSnapshot snapshot) {
        return new MlFeatureSnapshotResponse(
                snapshot.getId(),
                snapshot.getAccountId(),
                snapshot.getDatasetVersionId(),
                snapshot.getFeatureSetVersion(),
                snapshot.getCodeConfigVersion(),
                snapshot.getFeatureSchemaHash(),
                snapshot.getRowCount(),
                snapshot.getRejectedRowCount(),
                snapshot.getArtifactUri(),
                snapshot.getCreatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML feature metadata");
        }
    }

    private String sha256(String value) {
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

    private record FeatureConfig(
            String featureSetVersion,
            String codeConfigVersion,
            int rollingWindowSize,
            long missingGapSeconds,
            List<String> enabledFeatureFamilies) {
        static FeatureConfig from(MlFeatureSnapshotRequest request) {
            return new FeatureConfig(
                    request.featureSetVersion() == null || request.featureSetVersion().isBlank()
                            ? "feature-set-v1"
                            : request.featureSetVersion(),
                    request.codeConfigVersion() == null || request.codeConfigVersion().isBlank()
                            ? "analytics-feature-pipeline-v1"
                            : request.codeConfigVersion(),
                    request.rollingWindowSize() == null ? DEFAULT_ROLLING_WINDOW_SIZE : request.rollingWindowSize(),
                    request.missingGapSeconds() == null ? DEFAULT_MISSING_GAP_SECONDS : request.missingGapSeconds(),
                    request.enabledFeatureFamilies() == null || request.enabledFeatureFamilies().isEmpty()
                            ? DEFAULT_FEATURE_FAMILIES
                            : List.copyOf(request.enabledFeatureFamilies()));
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("featureSetVersion", featureSetVersion);
            value.put("codeConfigVersion", codeConfigVersion);
            value.put("rollingWindowSize", rollingWindowSize);
            value.put("missingGapSeconds", missingGapSeconds);
            value.put("enabledFeatureFamilies", enabledFeatureFamilies);
            return value;
        }
    }

    private record FeatureGeneration(long rowCount, long rejectedRowCount, Map<String, Object> schema) {
    }
}
