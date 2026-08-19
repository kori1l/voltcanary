package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MlFeatureConfig(
        String featureSetVersion,
        String codeConfigVersion,
        int rollingWindowSize,
        long missingGapSeconds,
        List<String> enabledFeatureFamilies,
        String modelFamily,
        Integer horizonDays,
        String featureTemplateId,
        String featureTemplateVersion) {

    private static final int DEFAULT_ROLLING_WINDOW_SIZE = 6;
    private static final long DEFAULT_MISSING_GAP_SECONDS = 6 * 60 * 60;
    private static final List<String> DEFAULT_FEATURE_FAMILIES = List.of(
            "rolling_stats",
            "baseline_deviation",
            "missing_data",
            "time",
            "label");

    public static MlFeatureConfig from(MlFeatureSnapshotRequest request) {
        return new MlFeatureConfig(
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

    public Map<String, Object> asMap() {
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
