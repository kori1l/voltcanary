package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlDeploymentPolicyDto;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MlModelRegistryResponseMapper {

    public MlModelVersionDto modelResponse(MlModelVersion model) {
        return new MlModelVersionDto(
                model.getId(),
                model.getAccountId(),
                model.getModelKey(),
                model.getVersion(),
                model.getFamily(),
                model.getAlgorithm(),
                model.getStatus(),
                model.getDatasetVersionId(),
                model.getFeatureSnapshotId(),
                model.getFeatureSchemaHash(),
                model.getArtifactUri(),
                model.getArtifactManifestUri(),
                model.getArtifactHash(),
                model.getArtifactSizeBytes(),
                model.getCompatibleDeviceTypesJson(),
                model.getSupportedMetricsJson(),
                model.getEvaluationMetricsJson(),
                modelOrigin(model),
                isDemoModel(model),
                isSystemHeuristic(model),
                model.getApprovedBy(),
                model.getApprovedAt(),
                model.getCreatedAt(),
                model.getTrainingRunId());
    }

    public MlDeploymentPolicyDto policyResponse(MlDeploymentPolicy policy) {
        return new MlDeploymentPolicyDto(
                policy.getId(),
                policy.getAccountId(),
                policy.getScope(),
                policy.getDeviceId(),
                policy.getDeviceType(),
                policy.getModelVersionId(),
                policy.getMode(),
                policy.getCanaryPercent(),
                policy.isFallbackEnabled(),
                policy.getUpdatedBy(),
                policy.getUpdatedAt());
    }

    private String modelOrigin(MlModelVersion model) {
        if (isDemoModel(model)) {
            return "DEMO";
        }
        if (isSystemHeuristic(model)) {
            return "SYSTEM_HEURISTIC";
        }
        return "TRAINED";
    }

    private boolean isDemoModel(MlModelVersion model) {
        return startsWith(model.getModelKey(), "demo-") || startsWith(model.getArtifactUri(), "artifact://demo/");
    }

    private boolean isSystemHeuristic(MlModelVersion model) {
        return model.getAccountId() == null
                || startsWith(model.getArtifactUri(), "artifact://system/")
                || contains(model.getAlgorithm(), "heuristic");
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private boolean contains(String value, String fragment) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(fragment);
    }
}
