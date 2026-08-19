package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.service.AnalyticsMlRuntimeDecisionService;
import com.voltcanary.analytics.service.AnalyticsPredictionTraceService;
import com.voltcanary.analytics.service.MlDatasetSupportService;
import com.voltcanary.analytics.service.MlDatasetValidationService;
import com.voltcanary.analytics.service.MlFeatureConfig;
import com.voltcanary.analytics.service.MlFeatureGeneration;
import com.voltcanary.analytics.service.MlFeatureGenerationService;
import com.voltcanary.analytics.service.MlImportedDatasetFeatureBuilder;
import com.voltcanary.analytics.service.MlInternalTelemetryFeatureBuilder;
import com.voltcanary.analytics.service.MlModelApprovalGateVerifier;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MlLifecycleExtractionCollaboratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void datasetValidationNormalizesInternalTelemetryRows() {
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        MaintenanceWindowReadModelRepository maintenanceRepository = mock(MaintenanceWindowReadModelRepository.class);
        TelemetryClient telemetryClient = mock(TelemetryClient.class);
        when(maintenanceRepository.findActiveOverlapping(deviceId, from, to)).thenReturn(List.of());
        when(telemetryClient.readings(deviceId, "activePowerW", from, to)).thenReturn(new TelemetryChartResponse(
                deviceId,
                "activePowerW",
                List.of(
                        new TelemetryChartResponse.Point(from.plusSeconds(60), 2.0),
                        new TelemetryChartResponse.Point(from, 1.0))));
        MlDatasetSupportService support = new MlDatasetSupportService(objectMapper);
        MlDatasetValidationService validationService = new MlDatasetValidationService(
                maintenanceRepository,
                telemetryClient,
                support);

        var result = validationService.validateTelemetry(new MlInternalDatasetRequest(
                "preview",
                null,
                List.of(deviceId),
                "washer",
                null,
                List.of("activePowerW"),
                from,
                to,
                null,
                null,
                null,
                2,
                7200L,
                null,
                null,
                null,
                null,
                null));

        assertThat(result.ready()).isTrue();
        assertThat(result.extractedRows()).hasSize(2);
        assertThat(result.extractedRows().getFirst().canonicalMetricName()).isEqualTo("active_power");
        assertThat(result.extractedRows().getFirst().timestamp()).isEqualTo(from);
    }

    @Test
    void featureGenerationDispatchesByDatasetSourceType() {
        MlInternalTelemetryFeatureBuilder internalBuilder = mock(MlInternalTelemetryFeatureBuilder.class);
        MlImportedDatasetFeatureBuilder importedBuilder = mock(MlImportedDatasetFeatureBuilder.class);
        MlFeatureGenerationService service = new MlFeatureGenerationService(internalBuilder, importedBuilder);
        MlDatasetVersion internalVersion = datasetVersion(MlDatasetSourceType.INTERNAL_TELEMETRY);
        MlDatasetVersion importedVersion = datasetVersion(MlDatasetSourceType.EXTERNAL_IMPORT);
        MlFeatureConfig config = MlFeatureConfig.from(new MlFeatureSnapshotRequest(
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        when(internalBuilder.generate(org.mockito.ArgumentMatchers.eq(internalVersion), org.mockito.ArgumentMatchers.eq(config), any(FeatureVectorContract.class)))
                .thenReturn(new MlFeatureGeneration(1, 0, Map.of(), List.of()));
        when(importedBuilder.generate(org.mockito.ArgumentMatchers.eq(importedVersion), org.mockito.ArgumentMatchers.eq(config), any(FeatureVectorContract.class)))
                .thenReturn(new MlFeatureGeneration(2, 0, Map.of(), List.of()));

        assertThat(service.generateFeatures(internalVersion, config).rowCount()).isEqualTo(1);
        assertThat(service.generateFeatures(importedVersion, config).rowCount()).isEqualTo(2);
        verify(internalBuilder).generate(org.mockito.ArgumentMatchers.eq(internalVersion), org.mockito.ArgumentMatchers.eq(config), any(FeatureVectorContract.class));
        verify(importedBuilder).generate(org.mockito.ArgumentMatchers.eq(importedVersion), org.mockito.ArgumentMatchers.eq(config), any(FeatureVectorContract.class));
    }

    @Test
    void approvalGateVerifierRejectsFailureModelsMissingGateMetadata() {
        MlModelVersion model = new MlModelVersion();
        model.setFamily(MlModelFamily.FAILURE_PREDICTION);
        model.setArtifactUri("artifact://models/failure/model.json");
        model.setArtifactManifestUri("artifact://models/failure/manifest.json");
        model.setArtifactHash("sha256:abc");
        model.setArtifactSizeBytes(100L);
        model.setEvaluationMetricsJson("{}");

        assertThatThrownBy(() -> new MlModelApprovalGateVerifier(objectMapper).verify(model))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("approval gates are missing");
    }

    @Test
    void runtimeDecisionRespectsCanaryAndCompatibilityFallbacks() {
        AnalyticsMlRuntimeDecisionService service = new AnalyticsMlRuntimeDecisionService(objectMapper);
        UUID accountId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();

        assertThat(service.canaryIncluded(accountId, deviceId, modelId, 100)).isTrue();
        assertThat(service.canaryIncluded(accountId, deviceId, modelId, 0)).isFalse();
        assertThat(service.compatibilityFallbackReason(
                modelDto(modelId, "[\"FRIDGE\"]", "[\"activePowerW\"]"),
                "washer",
                "activePowerW")).isEqualTo("INCOMPATIBLE_DEVICE_TYPE");
    }

    @Test
    void predictionTraceBuildsFallbackPayload() {
        AnalyticsPredictionTraceService service = new AnalyticsPredictionTraceService(objectMapper);
        UUID deviceId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        MlResolvedDeploymentPolicyResponse policy = new MlResolvedDeploymentPolicyResponse(
                MlDeploymentScope.ACCOUNT,
                UUID.randomUUID(),
                modelId,
                MlDeploymentMode.ACTIVE,
                "washer",
                null,
                true,
                true,
                null);

        var result = service.buildPredictionResult(
                deviceId,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                "input-hash",
                "UNAVAILABLE",
                policy,
                false,
                "ML_UNAVAILABLE_OR_BASELINE_FALLBACK",
                "washer",
                true);

        assertThat(result.getDeviceId()).isEqualTo(deviceId);
        assertThat(result.getModelId()).isEqualTo(modelId);
        assertThat(result.getFallbackReason()).isEqualTo("ML_UNAVAILABLE_OR_BASELINE_FALLBACK");
        assertThat(service.readJson(result.getPredictionJson()))
                .containsEntry("predictionPath", "FALLBACK")
                .containsEntry("inputHash", "input-hash");
    }

    private MlDatasetVersion datasetVersion(MlDatasetSourceType sourceType) {
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(UUID.randomUUID());
        version.setAccountId(UUID.randomUUID());
        version.setSourceType(sourceType);
        return version;
    }

    private MlModelVersionDto modelDto(UUID modelId, String deviceTypesJson, String metricsJson) {
        return new MlModelVersionDto(
                modelId,
                UUID.randomUUID(),
                "model",
                1,
                MlModelFamily.ANOMALY_DETECTION,
                "algorithm",
                null,
                null,
                null,
                "sha256:schema",
                "artifact://model",
                "artifact://manifest",
                "sha256:artifact",
                10L,
                deviceTypesJson,
                metricsJson,
                "{}",
                "TRAINED",
                false,
                false,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"));
    }
}
