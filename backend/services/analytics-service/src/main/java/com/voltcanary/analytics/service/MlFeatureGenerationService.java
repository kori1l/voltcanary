package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.ml.FeatureVectorContract;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlFeatureGenerationService {

    private final MlInternalTelemetryFeatureBuilder internalTelemetryFeatureBuilder;
    private final MlImportedDatasetFeatureBuilder importedDatasetFeatureBuilder;

    public MlFeatureGenerationService(
            MlInternalTelemetryFeatureBuilder internalTelemetryFeatureBuilder,
            MlImportedDatasetFeatureBuilder importedDatasetFeatureBuilder) {
        this.internalTelemetryFeatureBuilder = internalTelemetryFeatureBuilder;
        this.importedDatasetFeatureBuilder = importedDatasetFeatureBuilder;
    }

    public MlFeatureGeneration generateFeatures(MlDatasetVersion datasetVersion, MlFeatureConfig config) {
        FeatureVectorContract contract = FeatureVectorContract.forConfig(
                config.modelFamily(),
                config.horizonDays(),
                config.featureTemplateId(),
                config.featureTemplateVersion(),
                config.enabledFeatureFamilies());
        if (datasetVersion.getSourceType() == MlDatasetSourceType.INTERNAL_TELEMETRY) {
            return internalTelemetryFeatureBuilder.generate(datasetVersion, config, contract);
        }
        if (datasetVersion.getSourceType() == MlDatasetSourceType.EXTERNAL_IMPORT) {
            return importedDatasetFeatureBuilder.generate(datasetVersion, config, contract);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported ML dataset source type for feature generation");
    }
}
