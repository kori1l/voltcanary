package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlFeatureSnapshotGenerationService {

    private static final Logger log = LoggerFactory.getLogger(MlFeatureSnapshotGenerationService.class);

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureRowRepository featureRowRepository;
    private final MlFeatureGenerationService featureGenerationService;
    private final MlFeatureHashService hashService;
    private final MlFeatureSnapshotResponseMapper mapper;
    private final MeterRegistry meterRegistry;

    public MlFeatureSnapshotGenerationService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlFeatureRowRepository featureRowRepository,
            MlFeatureGenerationService featureGenerationService,
            MlFeatureHashService hashService,
            MlFeatureSnapshotResponseMapper mapper,
            MeterRegistry meterRegistry) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.featureRowRepository = featureRowRepository;
        this.featureGenerationService = featureGenerationService;
        this.hashService = hashService;
        this.mapper = mapper;
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

        MlFeatureConfig config = MlFeatureConfig.from(request);
        MlFeatureGeneration generation = featureGenerationService.generateFeatures(datasetVersion, config);
        String configJson = hashService.writeJson(config.asMap());
        String schemaJson = hashService.writeJson(generation.schema());
        String configHash = hashService.sha256(configJson);
        String schemaHash = hashService.sha256(schemaJson + "|" + configHash);
        String dataHash = hashService.featureDataHash(generation.rows());

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

        return mapper.response(snapshot);
    }

    private List<MlFeatureRow> featureRows(
            UUID accountId,
            MlDatasetVersion datasetVersion,
            UUID featureSnapshotId,
            List<MlFeatureRowData> rows,
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
                    featureRow.setFeatureValuesJson(hashService.writeJson(row.featureValues()));
                    featureRow.setLabelValuesJson(hashService.writeJson(row.labelValues()));
                    featureRow.setSplitAssignment(row.splitAssignment());
                    featureRow.setSourceLineageJson(hashService.writeJson(row.sourceLineage()));
                    featureRow.setQualityFlagsJson(hashService.writeJson(row.qualityFlags()));
                    featureRow.setCreatedAt(createdAt);
                    return featureRow;
                })
                .toList();
    }
}
