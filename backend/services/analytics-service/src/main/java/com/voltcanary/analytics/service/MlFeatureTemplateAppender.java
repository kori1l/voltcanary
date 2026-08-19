package com.voltcanary.analytics.service;

import com.voltcanary.analytics.ml.FeatureTemplateCatalog;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MlFeatureTemplateAppender {

    public void addTemplateFeatures(
            Map<String, Object> features,
            MlFeatureConfig config,
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
        String normalized = metricName == null ? "" : metricName.toLowerCase(Locale.ROOT);
        return normalized.contains("temp");
    }

    private boolean isFridgeCompatible(String deviceType) {
        String normalized = deviceType == null ? "" : deviceType.toLowerCase(Locale.ROOT);
        return normalized.contains("fridge") || normalized.contains("freezer") || normalized.contains("refrigerator");
    }
}
