package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MlDemoModelSeeder {

    private final MlModelVersionRepository modelVersionRepository;
    private final MlDeploymentAuditWriter auditWriter;

    public MlDemoModelSeeder(MlModelVersionRepository modelVersionRepository, MlDeploymentAuditWriter auditWriter) {
        this.modelVersionRepository = modelVersionRepository;
        this.auditWriter = auditWriter;
    }

    public void ensureDemoModels(UUID accountId) {
        if (!modelVersionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        modelVersionRepository.save(demoModel(
                accountId,
                "anomaly-baseline",
                1,
                MlModelFamily.ANOMALY_DETECTION,
                "baseline-statistical-anomaly-v1",
                MlModelStatus.APPROVED,
                List.of("GENERIC_APPLIANCE", "AIR_CONDITIONER", "FRIDGE"),
                List.of("voltage", "current", "activePowerW", "energyGrowth"),
                Map.of("precision", 0.86, "recall", 0.78, "f1", 0.82, "trainingRows", 12480),
                now.minusSeconds(180)));
        modelVersionRepository.save(demoModel(
                accountId,
                "degradation-score",
                1,
                MlModelFamily.DEGRADATION_SCORING,
                "rolling-degradation-score-v1",
                MlModelStatus.APPROVED,
                List.of("GENERIC_APPLIANCE", "PUMP", "HEATER"),
                List.of("activePowerW", "current", "temperatureC", "runtimeHours"),
                Map.of("mae", 0.11, "precision", 0.81, "recall", 0.74, "trainingRows", 9180),
                now.minusSeconds(120)));
        modelVersionRepository.save(demoModel(
                accountId,
                "energy-forecast",
                1,
                MlModelFamily.FORECASTING,
                "seasonal-energy-forecast-v1",
                MlModelStatus.CANDIDATE,
                List.of("GENERIC_APPLIANCE", "FRIDGE", "SERVER_NAS"),
                List.of("activePowerW", "energyDelta", "temperatureC"),
                Map.of("mape", 0.09, "rmse", 0.18, "trainingRows", 20160),
                now.minusSeconds(60)));
    }

    private MlModelVersion demoModel(
            UUID accountId,
            String modelKeySuffix,
            int version,
            MlModelFamily family,
            String algorithm,
            MlModelStatus status,
            List<String> compatibleDeviceTypes,
            List<String> supportedMetrics,
            Map<String, Object> evaluationMetrics,
            Instant createdAt) {
        MlModelVersion model = new MlModelVersion();
        model.setId(UUID.randomUUID());
        model.setAccountId(accountId);
        model.setModelKey("demo-" + accountId.toString().substring(0, 8) + "-" + modelKeySuffix);
        model.setVersion(version);
        model.setFamily(family);
        model.setAlgorithm(algorithm);
        model.setStatus(status);
        model.setFeatureSchemaHash("sha256:demo-" + modelKeySuffix);
        model.setArtifactUri("artifact://demo/" + modelKeySuffix + "/model.json");
        model.setArtifactManifestUri("artifact://demo/" + modelKeySuffix + "/manifest.json");
        model.setArtifactHash("sha256:demo-artifact-" + modelKeySuffix);
        model.setArtifactSizeBytes((long) auditWriter.writeJson(evaluationMetrics).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        model.setCompatibleDeviceTypesJson(auditWriter.writeJson(compatibleDeviceTypes));
        model.setSupportedMetricsJson(auditWriter.writeJson(supportedMetrics));
        model.setEvaluationMetricsJson(auditWriter.writeJson(evaluationMetrics));
        model.setCreatedAt(createdAt);
        return model;
    }
}
