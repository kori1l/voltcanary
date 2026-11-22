package com.voltcanary.analytics.ml;

import lombok.Builder;

/** Typed inference context shared by online prediction and training smoke inference. */
@Builder
public record MlInferenceModelPreference(
    String modelName,
    String modelVersionId,
    String modelFamily,
    String modelVersion,
    String modelType,
    String modelStatus,
    String artifactUri,
    String manifestUri,
    String artifactHash,
    Long artifactSizeBytes,
    Boolean allowBaselineHeuristic,
    String predictionMode,
    String deploymentMode,
    String policySource,
    String policyId,
    String resolvedDeviceType,
    Integer canaryPercent,
    Boolean canaryIncluded,
    String requiredFeatureSchemaHash,
    String requiredFeatureContractVersion,
    String requiredFeatureContractMetadataHash,
    String requiredFeatureSetVersion,
    String requiredFeatureGeneratorVersion,
    String requiredFeatureTemplateId,
    String requiredFeatureTemplateVersion,
    Integer requiredHorizonDays,
    String requiredFeatureConfigHash
) {}
