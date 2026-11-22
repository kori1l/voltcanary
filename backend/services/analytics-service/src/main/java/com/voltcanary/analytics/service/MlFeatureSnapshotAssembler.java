package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureRow;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MlFeatureSnapshotAssembler {

    private final MlFeatureHashService hashService;

    public record Prepared(MlFeatureSnapshot snapshot, List<MlFeatureRow> rows) {
        public Prepared {
            rows = List.copyOf(rows);
        }
    }

    public Prepared assemble(
        MlFeatureInputs inputs,
        MlFeatureConfig config,
        MlFeatureGeneration generation
    ) {
        MlDatasetVersion datasetVersion = inputs.version();
        UUID accountId = datasetVersion.getAccountId();
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
        snapshot.setImmutableRowReference(
            "ml_feature_rows:feature_snapshot_id=" + snapshot.getId()
        );
        snapshot.setModelFamily(config.modelFamily());
        snapshot.setHorizonDays(config.horizonDays());
        snapshot.setFeatureTemplateId(config.featureTemplateId());
        snapshot.setFeatureTemplateVersion(config.featureTemplateVersion());
        snapshot.setCreatedAt(now);
        return new Prepared(
            snapshot,
            featureRows(accountId, datasetVersion, snapshot.getId(), generation.rows(), now)
        );
    }

    private List<MlFeatureRow> featureRows(
        UUID accountId,
        MlDatasetVersion datasetVersion,
        UUID featureSnapshotId,
        List<MlFeatureRowData> rows,
        Instant createdAt
    ) {
        return rows
            .stream()
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
