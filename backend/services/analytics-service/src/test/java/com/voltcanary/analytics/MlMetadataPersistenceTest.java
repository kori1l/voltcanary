package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelAudit;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlLabelAuditRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class MlMetadataPersistenceTest {

    @Autowired
    private MlDatasetRepository datasetRepository;
    @Autowired
    private MlDatasetVersionRepository datasetVersionRepository;
    @Autowired
    private MlTrainingRunRepository trainingRunRepository;
    @Autowired
    private MlModelVersionRepository modelVersionRepository;
    @Autowired
    private MlDeploymentPolicyRepository deploymentPolicyRepository;
    @Autowired
    private MlFeatureSnapshotRepository featureSnapshotRepository;
    @Autowired
    private MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    @Autowired
    private MlLabelRepository labelRepository;
    @Autowired
    private MlLabelAuditRepository labelAuditRepository;

    @Test
    void repositoriesLoadWithProductionMlMetadataMappings() {
        assertThat(datasetRepository).isNotNull();
        assertThat(trainingRunRepository).isNotNull();
        assertThat(modelVersionRepository).isNotNull();
        assertThat(deploymentPolicyRepository).isNotNull();
        assertThat(featureSnapshotRepository).isNotNull();
        assertThat(canonicalMetricRowRepository).isNotNull();
    }

    @Test
    void contractEnumsExposeOpenSpecValues() {
        assertThat(MlDatasetStatus.values()).extracting(Enum::name)
                .containsExactly("DRAFT", "VALIDATING", "READY", "FAILED", "ARCHIVED");
        assertThat(MlLabelType.values()).extracting(Enum::name)
                .containsExactly(
                        "NORMAL",
                        "ANOMALY",
                        "DEGRADATION",
                        "MAINTENANCE",
                        "CONFIRMED_FAILURE",
                        "CORRECTIVE_MAINTENANCE",
                        "PREVENTIVE_MAINTENANCE",
                        "INSPECTION_NORMAL",
                        "FALSE_POSITIVE",
                        "SENSOR_FAULT",
                        "UNRESOLVED_ANOMALY",
                        "UNKNOWN");
        assertThat(MlLabelSource.values()).extracting(Enum::name)
                .containsExactly("MANUAL", "ALERT_OUTCOME", "MAINTENANCE_OUTCOME", "IMPORTED");
        assertThat(MlTrainingSplitStrategy.values()).extracting(Enum::name)
                .containsExactly("TIME_BASED", "DEVICE_BASED", "RANDOM");
        assertThat(MlModelFamily.values()).extracting(Enum::name)
                .containsExactly("ANOMALY_DETECTION", "DEGRADATION_SCORING", "FAILURE_PREDICTION", "FORECASTING");
        assertThat(MlTrainingRunStatus.values()).extracting(Enum::name)
                .containsExactly("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED");
        assertThat(MlModelStatus.values()).extracting(Enum::name)
                .containsExactly("CANDIDATE", "APPROVED", "ACTIVE", "REJECTED", "ARCHIVED");
        assertThat(MlDeploymentMode.values()).extracting(Enum::name)
                .containsExactly("SHADOW", "CANARY", "ACTIVE", "DISABLED");
        assertThat(MlDeploymentScope.values()).extracting(Enum::name)
                .containsExactly("SYSTEM", "ACCOUNT", "DEVICE_TYPE", "DEVICE");
    }

    @Test
    void datasetVersionContractExposesArtifactAndImmutableRowMetadata() {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setArtifactUri("s3://voltcanary-ml/datasets/version-1/rows.jsonl");
        version.setArtifactHash("sha256:artifact");
        version.setImmutableRowReference("ml_canonical_metric_rows:dataset_version_id=version-1");
        version.setDataHash("sha256:data");

        assertThat(version.getArtifactUri()).isEqualTo("s3://voltcanary-ml/datasets/version-1/rows.jsonl");
        assertThat(version.getArtifactHash()).isEqualTo("sha256:artifact");
        assertThat(version.getImmutableRowReference()).contains("ml_canonical_metric_rows");
        assertThat(version.getDataHash()).isEqualTo("sha256:data");
    }

    @Test
    void validationReportContractExposesQualityProfile() {
        MlValidationReport report = new MlValidationReport();
        report.setQualityProfileJson("{\"rowCount\":10,\"deviceCount\":2,\"metricCount\":1}");

        assertThat(report.getQualityProfileJson()).contains("rowCount");
        assertThat(report.getQualityProfileJson()).contains("deviceCount");
        assertThat(report.getQualityProfileJson()).contains("metricCount");
    }

    @Test
    void canonicalMetricRowContractExposesInternalAndExternalTrainingFields() {
        MlCanonicalMetricRow row = new MlCanonicalMetricRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(UUID.randomUUID());
        row.setDatasetVersionId(UUID.randomUUID());
        row.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        row.setDeviceId(UUID.randomUUID());
        row.setExternalDeviceKey("washer-legacy-1");
        row.setVirtualDeviceKey("internal:washer-legacy-1");
        row.setDeviceType("washer");
        row.setSourceId("telemetry-service");
        row.setSensorId("power-main");
        row.setTimestamp(Instant.parse("2026-07-01T00:00:00Z"));
        row.setMetricName("activePowerW");
        row.setCanonicalMetricName("active_power");
        row.setNumericValue(123.4);
        row.setRawUnit("W");
        row.setCanonicalUnit("W");
        row.setQualityFlags("[]");
        row.setMetadataJson("{\"firmware\":\"2.4.1\"}");
        row.setSourceLineage("{\"source\":\"internal_telemetry\"}");
        row.setCreatedAt(Instant.parse("2026-07-01T00:01:00Z"));

        assertThat(row.getAccountId()).isNotNull();
        assertThat(row.getDatasetVersionId()).isNotNull();
        assertThat(row.getSourceType()).isEqualTo(MlDatasetSourceType.INTERNAL_TELEMETRY);
        assertThat(row.getDeviceId()).isNotNull();
        assertThat(row.getExternalDeviceKey()).isEqualTo("washer-legacy-1");
        assertThat(row.getVirtualDeviceKey()).isEqualTo("internal:washer-legacy-1");
        assertThat(row.getDeviceType()).isEqualTo("washer");
        assertThat(row.getSourceId()).isEqualTo("telemetry-service");
        assertThat(row.getSensorId()).isEqualTo("power-main");
        assertThat(row.getTimestamp()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(row.getMetricName()).isEqualTo("activePowerW");
        assertThat(row.getCanonicalMetricName()).isEqualTo("active_power");
        assertThat(row.getNumericValue()).isEqualTo(123.4);
        assertThat(row.getRawUnit()).isEqualTo("W");
        assertThat(row.getCanonicalUnit()).isEqualTo("W");
        assertThat(row.getQualityFlags()).isEqualTo("[]");
        assertThat(row.getMetadataJson()).contains("firmware");
        assertThat(row.getSourceLineage()).contains("internal_telemetry");
    }

    @Test
    void generatedOutcomeLabelsCanBeDeletedAfterManualAuditCorrections() {
        UUID accountId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(accountId);
        dataset.setName("Failure labels");
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        datasetRepository.save(dataset);

        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(datasetVersionId);
        version.setDatasetId(dataset.getId());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(MlDatasetStatus.READY);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("test");
        version.setCreatedAt(now);
        datasetVersionRepository.save(version);

        MlLabel label = new MlLabel();
        label.setId(UUID.randomUUID());
        label.setAccountId(accountId);
        label.setDatasetVersionId(datasetVersionId);
        label.setDeviceId(UUID.randomUUID());
        label.setLabelStartAt(now);
        label.setLabelEndAt(now.plusSeconds(1));
        label.setLabel(MlLabelType.CONFIRMED_FAILURE);
        label.setSource(MlLabelSource.MAINTENANCE_OUTCOME);
        label.setHorizonDays(30);
        label.setGenerationVersion("failure-outcome-v1");
        label.setSourceOutcomeIdsJson("[]");
        label.setLineageHash("sha256:test");
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        labelRepository.save(label);

        MlLabelAudit audit = new MlLabelAudit();
        audit.setId(UUID.randomUUID());
        audit.setLabelId(label.getId());
        audit.setPreviousLabel(MlLabelType.CONFIRMED_FAILURE);
        audit.setNewLabel(MlLabelType.FALSE_POSITIVE);
        audit.setEditedAt(now.plusSeconds(60));
        audit.setReason("operator correction");
        labelAuditRepository.save(audit);

        labelRepository.deleteGeneratedOutcomeLabels(accountId, datasetVersionId, 30);

        assertThat(labelRepository.findById(label.getId())).isEmpty();
        assertThat(labelAuditRepository.findByLabelIdOrderByEditedAtDesc(label.getId())).isEmpty();
    }
}
