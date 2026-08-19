package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlImportedFile;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.ml.FeatureTemplateCatalog;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
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
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MlImportedFileRepository importedFileRepository;
    private final MlImportedMetricRowRepository importedMetricRowRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureRowRepository featureRowRepository;
    private final MlLabelRepository labelRepository;
    private final MlTrainingRunRepository trainingRunRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public MlFeaturePipelineService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlCanonicalMetricRowRepository canonicalMetricRowRepository,
            MlImportedFileRepository importedFileRepository,
            MlImportedMetricRowRepository importedMetricRowRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlFeatureRowRepository featureRowRepository,
            MlLabelRepository labelRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.importedFileRepository = importedFileRepository;
        this.importedMetricRowRepository = importedMetricRowRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.featureRowRepository = featureRowRepository;
        this.labelRepository = labelRepository;
        this.trainingRunRepository = trainingRunRepository;
        this.modelVersionRepository = modelVersionRepository;
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
        String schemaJson = writeJson(generation.schema());
        String configHash = sha256(configJson);
        String schemaHash = sha256(schemaJson + "|" + configHash);
        String dataHash = featureDataHash(generation.rows());

        Instant now = Instant.now();
        MlFeatureSnapshot snapshot = new MlFeatureSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setAccountId(accountId);
        snapshot.setDatasetVersionId(datasetVersion.getId());
        snapshot.setFeatureSetVersion(config.featureSetVersion());
        snapshot.setCodeConfigVersion(config.codeConfigVersion());
        snapshot.setFeatureConfigJson(configJson);
        snapshot.setFeatureSchemaHash(schemaHash);
        snapshot.setFeatureConfigHash(configHash);
        snapshot.setFeatureDataHash(dataHash);
        snapshot.setRowCount(generation.rowCount());
        snapshot.setRejectedRowCount(generation.rejectedRowCount());
        snapshot.setArtifactUri("db://ml_feature_snapshots/" + snapshot.getId());
        snapshot.setImmutableRowReference("ml_feature_rows:feature_snapshot_id=" + snapshot.getId());
        snapshot.setModelFamily(config.modelFamily());
        snapshot.setHorizonDays(config.horizonDays());
        snapshot.setFeatureTemplateId(config.featureTemplateId());
        snapshot.setFeatureTemplateVersion(config.featureTemplateVersion());
        snapshot.setCreatedAt(now);
        featureSnapshotRepository.save(snapshot);
        if (!generation.rows().isEmpty()) {
            featureRowRepository.saveAll(featureRows(accountId, datasetVersion, snapshot.getId(), generation.rows(), now));
        }
        meterRegistry.counter(
                        "analytics.ml.feature.generation.completed",
                        "source_type", datasetVersion.getSourceType().name(),
                        "feature_set_version", snapshot.getFeatureSetVersion())
                .increment();
        log.info("mlFeatureSnapshotGenerated featureSnapshotId={} datasetVersionId={} accountId={} sourceType={} rowCount={} rejectedRowCount={} featureSchemaHash={} featureDataHash={}",
                snapshot.getId(), datasetVersion.getId(), accountId, datasetVersion.getSourceType(),
                snapshot.getRowCount(), snapshot.getRejectedRowCount(), snapshot.getFeatureSchemaHash(), snapshot.getFeatureDataHash());

        return response(snapshot);
    }

    @Transactional(readOnly = true)
    public List<MlFeatureSnapshotResponse> list(UUID accountId, UUID datasetVersionId) {
        List<MlFeatureSnapshot> snapshots = datasetVersionId == null
                ? featureSnapshotRepository.findByAccountIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, "ACTIVE")
                : featureSnapshotRepository.findByAccountIdAndDatasetVersionIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, datasetVersionId, "ACTIVE");
        return snapshots.stream().map(this::response).toList();
    }

    @Transactional
    public MlFeatureSnapshotResponse archive(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String reason = requiredReason(request);
        if ("DELETED".equals(snapshot.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        stampLifecycle(snapshot, "ARCHIVED", actorId, reason);
        featureSnapshotRepository.save(snapshot);
        return response(snapshot);
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse impact(UUID accountId, UUID featureSnapshotId, String action) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String normalizedAction = normalizedAction(action);
        Map<String, Long> dependencies = featureSnapshotDependencies(featureSnapshotId);
        boolean blocked = "delete".equals(normalizedAction)
                && dependencies.values().stream().anyMatch(count -> count > 0);
        List<Map<String, Object>> dependencySummary = "delete".equals(normalizedAction)
                ? List.of(dependency("featureSnapshot", featureSnapshotId, dependencies))
                : List.of(dependency("featureSnapshot", featureSnapshotId, Map.of("status", snapshot.getLifecycleStatus())));
        String message = blocked
                ? "Feature snapshot is referenced and should be archived instead of deleted"
                : "Feature snapshot action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(normalizedAction, blocked, message, dependencySummary);
    }

    @Transactional
    public MlFeatureSnapshotResponse delete(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String reason = requiredReason(request);
        if ("DELETED".equals(snapshot.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        Map<String, Long> dependencies = featureSnapshotDependencies(featureSnapshotId);
        if (dependencies.values().stream().anyMatch(count -> count > 0)) {
            throw new MlLifecycleConflictException(
                    "FEATURE_SNAPSHOT_REFERENCED",
                    "Feature snapshot is referenced by training or model lineage and must be archived instead of deleted",
                    dependency("featureSnapshot", featureSnapshotId, dependencies));
        }
        stampLifecycle(snapshot, "DELETED", actorId, reason);
        featureSnapshotRepository.save(snapshot);
        return response(snapshot);
    }

    private Map<String, Long> featureSnapshotDependencies(UUID featureSnapshotId) {
        Map<String, Long> dependencies = new LinkedHashMap<>();
        dependencies.put("trainingRuns", trainingRunRepository.countByFeatureSnapshotId(featureSnapshotId));
        dependencies.put("modelVersions", modelVersionRepository.countByFeatureSnapshotId(featureSnapshotId));
        return dependencies;
    }

    private MlFeatureSnapshot findOwnedSnapshot(UUID accountId, UUID featureSnapshotId) {
        MlFeatureSnapshot snapshot = featureSnapshotRepository.findById(featureSnapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found"));
        if (!accountId.equals(snapshot.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        return snapshot;
    }

    private FeatureGeneration generateFeatures(MlDatasetVersion datasetVersion, FeatureConfig config) {
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                config.modelFamily(),
                config.horizonDays(),
                config.featureTemplateId(),
                config.featureTemplateVersion(),
                config.enabledFeatureFamilies());
        if (datasetVersion.getSourceType() == MlDatasetSourceType.INTERNAL_TELEMETRY) {
            return generateInternalTelemetryFeatures(datasetVersion, config, contract);
        }
        if (datasetVersion.getSourceType() != MlDatasetSourceType.EXTERNAL_IMPORT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported ML dataset source type for feature generation");
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
        LabelLookup labels = LabelLookup.from(labelRepository.findByAccountIdAndDatasetVersionId(
                datasetVersion.getAccountId(),
                datasetVersion.getId()));

        long rejected = 0;
        List<FeatureRowData> featureRows = new ArrayList<>();
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
                addTemplateFeatures(features, config, row.getDeviceType(), row.getMetricName(), row.getNumericValue(), baselineMean);
                Map<String, Object> labelValues = labels.valuesFor(
                        row.getMatchedDeviceId(),
                        row.getExternalDeviceKey(),
                        row.getMetricName(),
                        row.getTimestamp(),
                        config.horizonDays());
                featureRows.add(new FeatureRowData(
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
        return new FeatureGeneration(featureRows.size(), rejected, schema, featureRows);
    }

    private FeatureGeneration generateInternalTelemetryFeatures(
            MlDatasetVersion datasetVersion,
            FeatureConfig config,
            FeatureVectorContract contract) {
        List<MlCanonicalMetricRow> rows = canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersion.getId()).stream()
                .sorted(Comparator.comparing((MlCanonicalMetricRow row) -> row.getDeviceId() == null ? "" : row.getDeviceId().toString())
                        .thenComparing(row -> row.getVirtualDeviceKey() == null ? "" : row.getVirtualDeviceKey())
                        .thenComparing(MlCanonicalMetricRow::getCanonicalMetricName)
                        .thenComparing(MlCanonicalMetricRow::getTimestamp))
                .toList();

        Map<String, List<MlCanonicalMetricRow>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> internalEntityId(row) + "|" + row.getCanonicalMetricName(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        LabelLookup labels = LabelLookup.from(labelRepository.findByAccountIdAndDatasetVersionId(
                datasetVersion.getAccountId(),
                datasetVersion.getId()));

        List<FeatureRowData> featureRows = new ArrayList<>();
        for (List<MlCanonicalMetricRow> groupRows : groups.values()) {
            double baselineMean = groupRows.stream().mapToDouble(MlCanonicalMetricRow::getNumericValue).average().orElse(0);
            for (int index = 0; index < groupRows.size(); index++) {
                MlCanonicalMetricRow row = groupRows.get(index);
                int from = Math.max(0, index - config.rollingWindowSize() + 1);
                List<MlCanonicalMetricRow> window = groupRows.subList(from, index + 1);
                Map<String, Object> features = new LinkedHashMap<>();
                features.put("deviceKey", internalEntityId(row));
                features.put("metricName", row.getCanonicalMetricName());
                features.put("timestamp", row.getTimestamp().toString());
                if (config.enabledFeatureFamilies().contains("rolling_stats")) {
                    features.put("rollingMean", internalMean(window));
                    features.put("rollingMin", window.stream().mapToDouble(MlCanonicalMetricRow::getNumericValue).min().orElse(row.getNumericValue()));
                    features.put("rollingMax", window.stream().mapToDouble(MlCanonicalMetricRow::getNumericValue).max().orElse(row.getNumericValue()));
                    features.put("rollingStddev", internalStddev(window));
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
                if (config.enabledFeatureFamilies().contains("device_context")) {
                    features.put("deviceType", row.getDeviceType());
                    features.put("metricSchemaId", row.getCanonicalMetricName() + ":" + nullToBlank(row.getCanonicalUnit()));
                }
                addTemplateFeatures(features, config, row.getDeviceType(), row.getCanonicalMetricName(), row.getNumericValue(), baselineMean);
                featureRows.add(new FeatureRowData(
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
                                config.horizonDays()),
                        splitAssignment(index, groupRows.size()),
                        Map.of(
                                "source", "ml_canonical_metric_rows",
                                "canonicalRowId", row.getId().toString(),
                                "sourceType", row.getSourceType().name()),
                        qualityFlags(row)));
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
        return new FeatureGeneration(featureRows.size(), 0, schema, featureRows);
    }

    private List<MlFeatureRow> featureRows(
            UUID accountId,
            MlDatasetVersion datasetVersion,
            UUID featureSnapshotId,
            List<FeatureRowData> rows,
            Instant createdAt) {
        return rows.stream()
                .map(row -> {
                    MlFeatureRow featureRow = new MlFeatureRow();
                    featureRow.setId(UUID.randomUUID());
                    featureRow.setAccountId(accountId);
                    featureRow.setFeatureSnapshotId(featureSnapshotId);
                    featureRow.setDatasetVersionId(datasetVersion.getId());
                    featureRow.setEntityId(row.entityId());
                    featureRow.setDeviceId(row.deviceId());
                    featureRow.setVirtualDeviceKey(row.virtualDeviceKey());
                    featureRow.setDeviceType(row.deviceType());
                    featureRow.setPredictionAt(row.predictionAt());
                    featureRow.setHorizonDays(row.horizonDays());
                    featureRow.setFeatureValuesJson(writeJson(row.featureValues()));
                    featureRow.setLabelValuesJson(writeJson(row.labelValues()));
                    featureRow.setSplitAssignment(row.splitAssignment());
                    featureRow.setSourceLineageJson(writeJson(row.sourceLineage()));
                    featureRow.setQualityFlagsJson(writeJson(row.qualityFlags()));
                    featureRow.setCreatedAt(createdAt);
                    return featureRow;
                })
                .toList();
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

    private double internalMean(List<MlCanonicalMetricRow> rows) {
        return rows.stream().mapToDouble(MlCanonicalMetricRow::getNumericValue).average().orElse(0);
    }

    private double internalStddev(List<MlCanonicalMetricRow> rows) {
        double mean = internalMean(rows);
        return Math.sqrt(rows.stream()
                .mapToDouble(row -> Math.pow(row.getNumericValue() - mean, 2))
                .average()
                .orElse(0));
    }

    private void addTemplateFeatures(
            Map<String, Object> features,
            FeatureConfig config,
            String deviceType,
            String metricName,
            double value,
            double baselineMean) {
        FeatureTemplateCatalog.FeatureTemplate template =
                FeatureTemplateCatalog.resolve(config.featureTemplateId(), config.featureTemplateVersion());
        double loadRatio = baselineMean == 0 ? 0 : value / baselineMean;
        if (FeatureTemplateCatalog.DEFAULT_TEMPLATE_ID.equals(template.id())) {
            features.put("applianceLoadRatio", loadRatio);
            features.put("applianceActivityFlag", value > baselineMean);
            return;
        }
        if ("fridge-freezer".equals(template.id())) {
            features.put("fridgeTemperatureBandC", isTemperatureMetric(metricName) ? value : 0);
            features.put("compressorDutyProxy", value > baselineMean ? 1.0 : 0.0);
            features.put("defrostCycleRiskFlag", isFridgeCompatible(deviceType) && loadRatio > 1.5);
        }
    }

    private boolean isTemperatureMetric(String metricName) {
        String normalized = metricName == null ? "" : metricName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("temp");
    }

    private boolean isFridgeCompatible(String deviceType) {
        String normalized = deviceType == null ? "" : deviceType.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("fridge") || normalized.contains("freezer") || normalized.contains("refrigerator");
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
        if (row.getQualityFlags() == null || row.getQualityFlags().isBlank() || "[]".equals(row.getQualityFlags())) {
            return List.of();
        }
        return List.of(row.getQualityFlags());
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static boolean isPositiveFailureLabel(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    private record LabelLookup(Map<String, MlLabel> labels) {
        static LabelLookup from(List<MlLabel> labels) {
            Map<String, MlLabel> byKey = new LinkedHashMap<>();
            labels.stream()
                    .sorted(Comparator.comparing(MlLabel::getCreatedAt))
                    .forEach(label -> keys(label).forEach(key -> byKey.put(key, label)));
            return new LabelLookup(byKey);
        }

        Map<String, Object> valuesFor(
                UUID deviceId,
                String externalDeviceKey,
                String metricName,
                Instant predictionAt,
                Integer horizonDays) {
            MlLabel label = keys(deviceId, externalDeviceKey, metricName, predictionAt, horizonDays).stream()
                    .map(labels::get)
                    .filter(item -> item != null)
                    .findFirst()
                    .orElse(null);
            if (label == null) {
                return Map.of();
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("label", label.getLabel().name());
            values.put("failureWithinHorizon", isPositiveFailureLabel(label.getLabel()));
            values.put("failureOutcomeType", label.getLabel().name());
            values.put("horizonDays", label.getHorizonDays());
            values.put("generationVersion", label.getGenerationVersion() == null ? "" : label.getGenerationVersion());
            values.put("lineageHash", label.getLineageHash() == null ? "" : label.getLineageHash());
            values.put("sourceOutcomeIdsJson", label.getSourceOutcomeIdsJson() == null ? "[]" : label.getSourceOutcomeIdsJson());
            return values;
        }

        private static List<String> keys(MlLabel label) {
            return keys(
                    label.getDeviceId(),
                    label.getExternalDeviceKey(),
                    label.getMetricName(),
                    label.getLabelStartAt(),
                    label.getHorizonDays());
        }

        private static List<String> keys(
                UUID deviceId,
                String externalDeviceKey,
                String metricName,
                Instant predictionAt,
                Integer horizonDays) {
            String horizon = horizonDays == null ? "" : horizonDays.toString();
            String metric = metricName == null ? "" : metricName;
            List<String> keys = new ArrayList<>();
            if (deviceId != null) {
                keys.add("device|" + deviceId + "|" + metric + "|" + predictionAt + "|" + horizon);
            }
            if (externalDeviceKey != null && !externalDeviceKey.isBlank()) {
                keys.add("external|" + externalDeviceKey + "|" + metric + "|" + predictionAt + "|" + horizon);
            }
            return keys;
        }
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

    private String featureDataHash(List<FeatureRowData> rows) {
        return sha256(rows.stream()
                .sorted(Comparator.comparing(FeatureRowData::entityId).thenComparing(FeatureRowData::predictionAt))
                .map(row -> row.entityId() + "|" + row.predictionAt() + "|" + writeJson(row.featureValues()) + "|" + writeJson(row.labelValues()))
                .collect(Collectors.joining("\n")));
    }

    private MlFeatureSnapshotResponse response(MlFeatureSnapshot snapshot) {
        return new MlFeatureSnapshotResponse(
                snapshot.getId(),
                snapshot.getAccountId(),
                snapshot.getDatasetVersionId(),
                snapshot.getFeatureSetVersion(),
                snapshot.getCodeConfigVersion(),
                snapshot.getFeatureSchemaHash(),
                snapshot.getFeatureDataHash(),
                snapshot.getFeatureConfigHash(),
                snapshot.getRowCount(),
                snapshot.getRejectedRowCount(),
                snapshot.getArtifactUri(),
                snapshot.getImmutableRowReference(),
                snapshot.getModelFamily(),
                snapshot.getHorizonDays(),
                snapshot.getFeatureTemplateId(),
                snapshot.getFeatureTemplateVersion(),
                snapshot.getLifecycleStatus(),
                snapshot.getCreatedAt());
    }

    private String requiredReason(MlLifecycleActionRequest request) {
        String value = request == null ? null : request.reason();
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lifecycle reason is required");
        }
        return value.trim();
    }

    private void stampLifecycle(MlFeatureSnapshot snapshot, String status, UUID actorId, String reason) {
        snapshot.setLifecycleStatus(status);
        snapshot.setLifecycleActorId(actorId);
        snapshot.setLifecycleAt(Instant.now());
        snapshot.setLifecycleReason(reason);
    }

    private Map<String, Object> dependency(String type, UUID id, Map<String, ?> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id.toString());
        value.put("details", details);
        return value;
    }

    private String normalizedAction(String action) {
        if (action == null || action.isBlank()) {
            return "delete";
        }
        return action.trim().toLowerCase(java.util.Locale.ROOT);
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
            List<String> enabledFeatureFamilies,
            String modelFamily,
            Integer horizonDays,
            String featureTemplateId,
            String featureTemplateVersion) {
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
                            : List.copyOf(request.enabledFeatureFamilies()),
                    request.modelFamily() == null || request.modelFamily().isBlank()
                            ? "ANOMALY_DETECTION"
                            : request.modelFamily(),
                    request.horizonDays(),
                    request.featureTemplateId() == null || request.featureTemplateId().isBlank()
                            ? "generic-appliance"
                            : request.featureTemplateId(),
                    request.featureTemplateVersion() == null || request.featureTemplateVersion().isBlank()
                            ? "template-v1"
                            : request.featureTemplateVersion());
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("featureSetVersion", featureSetVersion);
            value.put("codeConfigVersion", codeConfigVersion);
            value.put("rollingWindowSize", rollingWindowSize);
            value.put("missingGapSeconds", missingGapSeconds);
            value.put("enabledFeatureFamilies", enabledFeatureFamilies);
            value.put("modelFamily", modelFamily);
            value.put("horizonDays", horizonDays);
            value.put("featureTemplateId", featureTemplateId);
            value.put("featureTemplateVersion", featureTemplateVersion);
            return value;
        }
    }

    private record FeatureGeneration(long rowCount, long rejectedRowCount, Map<String, Object> schema, List<FeatureRowData> rows) {
    }

    private record FeatureRowData(
            UUID deviceId,
            String virtualDeviceKey,
            String deviceType,
            Instant predictionAt,
            Integer horizonDays,
            String entityId,
            Map<String, Object> featureValues,
            Map<String, Object> labelValues,
            String splitAssignment,
            Map<String, Object> sourceLineage,
            List<String> qualityFlags) {
    }
}
